// --- Escena básica ---
const nativeApp = document.documentElement.classList.contains('android-app');
const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(75, window.innerWidth / window.innerHeight, 0.1, 1000);
const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setSize(window.innerWidth, window.innerHeight);
document.body.appendChild(renderer.domElement);

const light = new THREE.DirectionalLight(0xffffff, 1);
light.position.set(5, 10, 7.5);
scene.add(light);
scene.add(new THREE.AmbientLight(0x404040));

const gridHelper = new THREE.GridHelper(10, 10);
if (!nativeApp) scene.add(gridHelper);

camera.position.set(0, 2, 2);
camera.lookAt(new THREE.Vector3(0, 1, 0));

let modelToRotate = null;
let targetQuaternion = new THREE.Quaternion();
let smoothQuaternion = new THREE.Quaternion();
const manualQuaternion = new THREE.Quaternion();
const manualEuler = new THREE.Euler(0, 0, 0, 'ZYX');
const smoothingMs = 40;
let lastFrameTime = performance.now();
let sensorOnline = false;
let sensorSamplePending = false;
let firstSensorSampleApplied = false;
let lastReadoutTime = -Infinity;
let autoCalibrateOnNextBleSample = nativeApp;

// Convierte los ejes del BNO08x (Z vertical) a los de la escena (Y vertical).
const sensorToScene = new THREE.Quaternion();
sensorToScene.setFromEuler(new THREE.Euler(-Math.PI / 2, 0, -Math.PI / 2));
const sceneToSensor = sensorToScene.clone().invert();
// Desde la vista frontal de la cámara, +90° en Y apunta a la derecha.
const calibratedFacing = new THREE.Quaternion().setFromAxisAngle(
  new THREE.Vector3(0, 1, 0), Math.PI / 2
);
const calibrationSensorInverse = new THREE.Quaternion();
const calibrationManualInverse = new THREE.Quaternion();
const alignedSensorQuat = new THREE.Quaternion();
const tiltAlignment = new THREE.Quaternion();
const tiltAlignmentInverse = new THREE.Quaternion();
const verticalAxis = new THREE.Vector3(0, 1, 0);
const defaultAlignmentDegrees = 115.5;
const defaultInvertForward = true;
const defaultInvertSide = true;
let alignmentDegrees = defaultAlignmentDegrees;
let invertForward = defaultInvertForward;
let invertSide = defaultInvertSide;
let forwardSensorQuat = null;
let calibrated = false;

let lastSensorQuat = null;

function calibratedMotion(sensorQuat, result) {
  result.copy(tiltAlignment)
    .multiply(sensorToScene)
    .multiply(sensorQuat)
    .multiply(calibrationSensorInverse)
    .multiply(sceneToSensor)
    .multiply(tiltAlignmentInverse)
    .normalize();
  // Corrige por separado los sentidos de inclinación frontal y lateral.
  // El componente Y (giro vertical) conserva su sentido.
  result.set(
    invertForward ? -result.x : result.x,
    result.y,
    invertSide ? -result.z : result.z,
    result.w
  );
  return result;
}

function updateTargetQuaternion(sensorQuat) {
  if (calibrated) {
    calibratedMotion(sensorQuat, alignedSensorQuat);
    targetQuaternion.copy(calibratedFacing)
      .multiply(alignedSensorQuat)
      .multiply(calibrationManualInverse);
  } else {
    targetQuaternion.copy(sensorToScene).multiply(sensorQuat);
  }
}

// --- Carga del modelo 3D local ---
const loader = new THREE.GLTFLoader();
loader.load('/cubone.glb', (gltf) => {
  const bounds = new THREE.Box3().setFromObject(gltf.scene);
  const size = bounds.getSize(new THREE.Vector3());
  const maxDimension = Math.max(size.x, size.y, size.z);
  if (maxDimension > 0) {
    gltf.scene.scale.multiplyScalar(1.5 / maxDimension);
    bounds.setFromObject(gltf.scene);
    gltf.scene.position.sub(bounds.getCenter(new THREE.Vector3()));
  }

  modelToRotate = new THREE.Group();
  modelToRotate.position.y = 1;
  modelToRotate.add(gltf.scene);
  scene.add(modelToRotate);
  document.getElementById('estado').textContent = lastSensorQuat
    ? 'Cubone y sensor conectados'
    : 'Cubone cargado; esperando sensor';
}, undefined, (error) => {
  console.error('Error al cargar cubone.glb:', error);
  document.getElementById('estado').textContent = 'No se pudo cargar cubone.glb';
});

// --- Entrada de orientación: SSE en navegador o BLE en la app Android ---
function acceptQuaternion(x, y, z, w) {
  if (![x, y, z, w].every(Number.isFinite)) return;
  const lengthSquared = x * x + y * y + z * z + w * w;
  if (lengthSquared < 0.5) return;
  if (!lastSensorQuat) lastSensorQuat = new THREE.Quaternion();
  lastSensorQuat.set(x, y, z, w).normalize();
  sensorSamplePending = true;
  if (autoCalibrateOnNextBleSample) {
    applyCalibration();
    autoCalibrateOnNextBleSample = false;
  }
  if (!sensorOnline) {
    document.getElementById('estado').textContent = 'Cubone y sensor conectados';
  }
  sensorOnline = true;
}

function markSensorDisconnected() {
  sensorOnline = false;
  document.getElementById('lecturaInclinacion').textContent = 'Movimiento: sensor desconectado';
  if (modelToRotate) {
    document.getElementById('estado').textContent = 'Conexión perdida';
  }
}

window.receiveBleQuaternion = acceptQuaternion;
window.onBleDisconnected = () => {
  markSensorDisconnected();
  autoCalibrateOnNextBleSample = nativeApp;
};
window.updateBleStatus = (message, connected) => {
  document.getElementById('bleStatus').textContent = message;
  const button = document.getElementById('bleConnect');
  const busy = /^(Buscando|Conectando|Leyendo)/.test(message);
  button.dataset.connected = String(connected);
  button.disabled = busy;
  button.textContent = connected
    ? 'Desconectar'
    : busy ? 'Conectando…'
      : message === 'Pulsa Conectar Bluetooth' || message === 'Bluetooth desconectado'
        ? 'Conectar Bluetooth' : 'Reintentar Bluetooth';
  button.title = message;
};

if (nativeApp) {
  const button = document.getElementById('bleConnect');
  if (window.AndroidBle) {
    button.addEventListener('click', () => {
      if (button.dataset.connected === 'true') {
        window.AndroidBle.disconnect();
      } else {
        autoCalibrateOnNextBleSample = true;
        window.AndroidBle.connect();
      }
    });
  } else {
    button.textContent = 'Bluetooth no disponible';
    button.disabled = true;
  }
}

// SSE se abre después del primer render para evitar muestras acumuladas al cargar.
let evtSource = null;
function connectToSensor() {
  if (nativeApp || evtSource) return;
  evtSource = new EventSource('/events');
  evtSource.addEventListener('quat', (event) => {
    const data = JSON.parse(event.data);
    acceptQuaternion(data.x, data.y, data.z, data.w);
  });
  evtSource.addEventListener('error', markSensorDisconnected);
}

// --- Animación principal ---
function animate(now) {
  requestAnimationFrame(animate);
  const frameMs = Math.min(100, now - lastFrameTime);
  lastFrameTime = now;

  if (sensorSamplePending) {
    updateTargetQuaternion(lastSensorQuat);
    if (!firstSensorSampleApplied) {
      smoothQuaternion.copy(targetQuaternion);
      firstSensorSampleApplied = true;
    }
    sensorSamplePending = false;
  }
  smoothQuaternion.slerp(targetQuaternion, 1 - Math.exp(-frameMs / smoothingMs));

  if (modelToRotate) {
    modelToRotate.quaternion.copy(smoothQuaternion).multiply(manualQuaternion);
    renderer.render(scene, camera);
    connectToSensor();
  }
  if (calibrated && sensorOnline && now - lastReadoutTime >= 150) {
    updateTiltReadout();
    lastReadoutTime = now;
  }
}
requestAnimationFrame(animate);

// --- UI ---
document.querySelectorAll('#rotX, #rotY, #rotZ, #gananciaInput').forEach(input => {
  input.addEventListener('input', updateUI);
});

function updateManualQuaternion() {
  const gain = Number(document.getElementById('gananciaInput').value);
  manualEuler.set(
    Number(document.getElementById('rotX').value) * gain,
    Number(document.getElementById('rotY').value) * gain,
    Number(document.getElementById('rotZ').value) * gain,
    'ZYX'
  );
  manualQuaternion.setFromEuler(manualEuler);
}

function updateUI(event) {
  const { id, value } = event.target;
  const spanId = id === 'gananciaInput' ? 'valG' : `val${id.slice(3)}`;
  document.getElementById(spanId).textContent = Number(value).toFixed(2);
  updateManualQuaternion();
}
updateManualQuaternion();

// --- Ajuste del eje de inclinación ---
function setAlignmentDegrees(value) {
  const numeric = Number(value);
  alignmentDegrees = Number.isFinite(numeric)
    ? Number(((((numeric + 180) % 360) + 360) % 360 - 180).toFixed(1))
    : defaultAlignmentDegrees;
  document.getElementById('alineacionInput').value = alignmentDegrees;
  document.getElementById('valAlineacion').textContent = `${alignmentDegrees}°`;
  tiltAlignment.setFromAxisAngle(verticalAxis, alignmentDegrees * Math.PI / 180);
  tiltAlignmentInverse.copy(tiltAlignment).invert();
  if (calibrated && lastSensorQuat) {
    updateTargetQuaternion(lastSensorQuat);
    updateTiltReadout();
  }
}

document.getElementById('alineacionInput').addEventListener('change', event => {
  setAlignmentDegrees(event.target.value);
});
document.getElementById('alineacionMenos').addEventListener('click', () => {
  setAlignmentDegrees(alignmentDegrees - 5);
});
document.getElementById('alineacionMas').addEventListener('click', () => {
  setAlignmentDegrees(alignmentDegrees + 5);
});
document.getElementById('alineacionMenos90').addEventListener('click', () => {
  setAlignmentDegrees(alignmentDegrees - 90);
});
document.getElementById('alineacionMas90').addEventListener('click', () => {
  setAlignmentDegrees(alignmentDegrees + 90);
});
document.getElementById('alineacionReset').addEventListener('click', () => {
  setAlignmentDegrees(defaultAlignmentDegrees);
  setInvertForward(defaultInvertForward);
  setInvertSide(defaultInvertSide);
});
setAlignmentDegrees(defaultAlignmentDegrees);

function setInvertForward(value) {
  invertForward = Boolean(value);
  const button = document.getElementById('invertirAdelante');
  button.textContent = invertForward
    ? 'Adelante/atrás: invertido'
    : 'Adelante/atrás: normal';
  button.setAttribute('aria-pressed', String(invertForward));
  if (calibrated && lastSensorQuat) {
    updateTargetQuaternion(lastSensorQuat);
    updateTiltReadout();
  }
}

document.getElementById('invertirAdelante').addEventListener('click', () => {
  setInvertForward(!invertForward);
});
setInvertForward(defaultInvertForward);

function setInvertSide(value) {
  invertSide = Boolean(value);
  const button = document.getElementById('invertirLado');
  button.textContent = invertSide
    ? 'Izquierda/derecha: invertido'
    : 'Izquierda/derecha: normal';
  button.setAttribute('aria-pressed', String(invertSide));
  if (calibrated && lastSensorQuat) {
    updateTargetQuaternion(lastSensorQuat);
    updateTiltReadout();
  }
}

document.getElementById('invertirLado').addEventListener('click', () => {
  setInvertSide(!invertSide);
});
setInvertSide(defaultInvertSide);

function tiltComponents(sensorQuat) {
  if (!calibrated || !sensorQuat) return null;
  const motion = calibratedMotion(sensorQuat, new THREE.Quaternion());
  if (motion.w < 0) motion.set(-motion.x, -motion.y, -motion.z, -motion.w);
  const magnitude = Math.hypot(motion.x, motion.y, motion.z);
  const scale = magnitude > 0
    ? 2 * Math.atan2(magnitude, motion.w) * 180 / Math.PI / magnitude
    : 0;
  return {
    frente: motion.x * scale,
    lado: motion.z * scale,
    giro: motion.y * scale
  };
}

function updateTiltReadout() {
  const values = tiltComponents(lastSensorQuat);
  document.getElementById('lecturaInclinacion').textContent = values
    ? `Movimiento: frente ${values.frente.toFixed(1)}° | lado ${values.lado.toFixed(1)}°`
    : 'Movimiento: calibra primero';
}

function measureForwardMotion() {
  if (!calibrated || !forwardSensorQuat) return null;
  const motion = sensorToScene.clone()
    .multiply(forwardSensorQuat)
    .multiply(calibrationSensorInverse)
    .multiply(sceneToSensor)
    .normalize();
  if (motion.w < 0) motion.set(-motion.x, -motion.y, -motion.z, -motion.w);
  const horizontal = Math.hypot(motion.x, motion.z);
  const motionMagnitude = Math.hypot(motion.x, motion.y, motion.z);
  let suggestedDegrees = Math.atan2(motion.z, motion.x) * 180 / Math.PI;
  // Hay dos alineaciones posibles, separadas por 180°. Conserva el sentido
  // actual de adelante/atrás al aprender una corrección fina del eje.
  const invertedDegrees = suggestedDegrees > 0 ? suggestedDegrees - 180 : suggestedDegrees + 180;
  const angularDistance = (a, b) => Math.abs(((((a - b + 180) % 360) + 360) % 360) - 180);
  if (angularDistance(invertedDegrees, alignmentDegrees) < angularDistance(suggestedDegrees, alignmentDegrees)) {
    suggestedDegrees = invertedDegrees;
  }
  return {
    motion,
    angleDegrees: 2 * Math.atan2(motionMagnitude, motion.w) * 180 / Math.PI,
    horizontal,
    yawFraction: motionMagnitude > 0 ? Math.abs(motion.y) / motionMagnitude : 0,
    suggestedDegrees
  };
}

document.getElementById('aprenderAdelante').addEventListener('click', () => {
  const status = document.getElementById('ajusteEstado');
  if (!calibrated || !sensorOnline || !lastSensorQuat) {
    status.textContent = 'Calibra primero con la placa acostada y el sensor conectado.';
    return;
  }
  forwardSensorQuat = lastSensorQuat.clone();
  const measurement = measureForwardMotion();
  if (measurement.angleDegrees < 8 || measurement.horizontal < 0.05) {
    status.textContent = 'Inclina la placa unos 25° hacia adelante y vuelve a pulsar.';
    return;
  }
  setAlignmentDegrees(Math.round(measurement.suggestedDegrees * 10) / 10);
  status.textContent = measurement.yawFraction > 0.35
    ? `Eje ajustado a ${alignmentDegrees}°. La muestra también tiene giro horizontal; copia el diagnóstico si sigue diagonal.`
    : `Eje ajustado a ${alignmentDegrees}°. Vuelve a inclinar la placa para comprobarlo.`;
});

function quaternionValues(q) {
  return q ? [q.x, q.y, q.z, q.w].map(value => Number(value.toFixed(6))) : null;
}

function buildDiagnostic() {
  const measurement = measureForwardMotion();
  return JSON.stringify({
    proyecto: 'GemeloDigitalEsp32',
    tipo: 'diagnostico-inclinacion-v3',
    sensorConectado: sensorOnline,
    calibrado: calibrated,
    alineacionGrados: alignmentDegrees,
    adelanteInvertido: invertForward,
    ladoInvertido: invertSide,
    controlesManuales: {
      rotX: document.getElementById('rotX').value,
      rotY: document.getElementById('rotY').value,
      rotZ: document.getElementById('rotZ').value,
      ganancia: document.getElementById('gananciaInput').value
    },
    sensorNeutro: calibrated ? quaternionValues(calibrationSensorInverse.clone().invert()) : null,
    sensorAdelante: quaternionValues(forwardSensorQuat),
    sensorActual: quaternionValues(lastSensorQuat),
    inclinacionActualGrados: tiltComponents(lastSensorQuat),
    movimientoAdelanteSinAjuste: measurement ? quaternionValues(measurement.motion) : null,
    anguloMedidoGrados: measurement ? Number(measurement.angleDegrees.toFixed(2)) : null,
    giroHorizontalFraccion: measurement ? Number(measurement.yawFraction.toFixed(3)) : null,
    alineacionSugeridaGrados: measurement ? Number(measurement.suggestedDegrees.toFixed(1)) : null
  }, null, 2);
}

document.getElementById('anotar').addEventListener('click', async () => {
  const textarea = document.getElementById('diagnostico');
  textarea.value = buildDiagnostic();
  textarea.focus();
  textarea.select();
  let copied = false;
  try { copied = document.execCommand('copy'); } catch (error) { /* copia manual */ }
  if (!copied && navigator.clipboard && navigator.clipboard.writeText) {
    try { await navigator.clipboard.writeText(textarea.value); copied = true; } catch (error) { /* copia manual */ }
  }
  document.getElementById('ajusteEstado').textContent = copied
    ? 'Diagnóstico copiado. Pégalo en el chat.'
    : 'Selecciona y copia el texto del recuadro para pegarlo en el chat.';
});

// --- Calibración manual en navegador y automática en Android ---
function applyCalibration() {
  if (lastSensorQuat) {
    calibrationSensorInverse.copy(lastSensorQuat).invert();
    calibrationManualInverse.copy(manualQuaternion).invert();
    forwardSensorQuat = null;
    calibrated = true;
    updateTargetQuaternion(lastSensorQuat);
    sensorSamplePending = false;
    firstSensorSampleApplied = true;
    updateTiltReadout();
    smoothQuaternion.copy(targetQuaternion);
    document.getElementById('estado').textContent = 'Calibrado: Cubone mira a la derecha';
    document.getElementById('ajusteEstado').textContent = `Calibrado: eje ${alignmentDegrees}°; frente ${invertForward ? 'invertido' : 'normal'}, lado ${invertSide ? 'invertido' : 'normal'}.`;
    console.log('Calibración aplicada: Cubone mira a la derecha.');
  } else {
    document.getElementById('estado').textContent = 'Esperando datos del sensor para calibrar';
    console.log("Esperando datos del sensor para calibrar...");
  }
}

document.getElementById('calibrar').addEventListener('click', applyCalibration);

// --- Redimensionar ventana ---
window.addEventListener('resize', () => {
  camera.aspect = window.innerWidth / window.innerHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(window.innerWidth, window.innerHeight);
});
