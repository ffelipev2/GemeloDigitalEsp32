const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(75, innerWidth / innerHeight, 0.1, 1000);
const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setPixelRatio(Math.min(devicePixelRatio || 1, 1.5));
renderer.setSize(innerWidth, innerHeight);
document.body.appendChild(renderer.domElement);

const light = new THREE.DirectionalLight(0xffffff, 1);
light.position.set(5, 10, 7.5);
scene.add(light, new THREE.AmbientLight(0x404040));
camera.position.set(0, 2, 2);
camera.lookAt(new THREE.Vector3(0, 1, 0));

const button = document.getElementById('bleButton');
const statusLabel = document.getElementById('connectionStatus');
let model = null;
let connected = false;
let statusText = 'Bluetooth desconectado';
let needsCalibration = true;
let lastSampleTime = 0;
let stale = false;

// Ejes y sentidos medidos con la placa del proyecto.
const sensorToScene = new THREE.Quaternion()
  .setFromEuler(new THREE.Euler(-Math.PI / 2, 0, -Math.PI / 2));
const sceneToSensor = sensorToScene.clone().invert();
const facingRight = new THREE.Quaternion()
  .setFromAxisAngle(new THREE.Vector3(0, 1, 0), Math.PI / 2);
const tiltAlignment = new THREE.Quaternion()
  .setFromAxisAngle(new THREE.Vector3(0, 1, 0), 115.5 * Math.PI / 180);
const inverseTiltAlignment = tiltAlignment.clone().invert();
const neutralInverse = new THREE.Quaternion();
const sensorQuaternion = new THREE.Quaternion();
const motion = new THREE.Quaternion();
const target = new THREE.Quaternion();
const smoothed = new THREE.Quaternion();
const smoothingMs = 40;
let lastFrameTime = performance.now();

new THREE.GLTFLoader().load('/cubone.glb', gltf => {
  const bounds = new THREE.Box3().setFromObject(gltf.scene);
  const size = bounds.getSize(new THREE.Vector3());
  const largest = Math.max(size.x, size.y, size.z);
  if (largest > 0) {
    gltf.scene.scale.multiplyScalar(1.5 / largest);
    bounds.setFromObject(gltf.scene);
    gltf.scene.position.sub(bounds.getCenter(new THREE.Vector3()));
  }
  model = new THREE.Group();
  model.position.y = 1;
  model.add(gltf.scene);
  scene.add(model);
}, undefined, error => {
  console.error('No se pudo cargar Cubone:', error);
  statusLabel.textContent = 'No se pudo cargar el modelo';
});

function showButtonState() {
  statusLabel.textContent = statusText;
  button.title = statusText;
  if (connected) {
    button.disabled = false;
    button.textContent = !lastSampleTime ? 'Esperando sensor · Desconectar'
      : stale ? 'Sin datos · Desconectar' : 'Desconectar';
    return;
  }
  const busy = /^(Buscando|Conectando|Leyendo)/.test(statusText);
  button.disabled = busy;
  button.textContent = busy ? 'Conectando…'
    : statusText.startsWith('Activa Bluetooth') ? 'Activar Bluetooth'
      : statusText.startsWith('Permiso') ? 'Permiso BLE · Reintentar'
        : statusText === 'Bluetooth desconectado' || statusText === 'Pulsa Conectar Bluetooth'
          ? 'Conectar Bluetooth' : 'Reintentar Bluetooth';
}

window.updateBleStatus = (message, isConnected) => {
  statusText = message;
  connected = Boolean(isConnected);
  if (!connected) {
    lastSampleTime = 0;
    stale = false;
  }
  showButtonState();
};

window.onBleDisconnected = () => {
  connected = false;
  needsCalibration = true;
  lastSampleTime = 0;
  stale = false;
  statusText = 'Bluetooth desconectado';
  showButtonState();
};

window.receiveBleQuaternion = (x, y, z, w) => {
  if (![x, y, z, w].every(Number.isFinite)) return;
  const lengthSquared = x * x + y * y + z * z + w * w;
  if (lengthSquared < 0.5 || lengthSquared > 1.5) return;
  sensorQuaternion.set(x, y, z, w).normalize();

  if (needsCalibration) {
    neutralInverse.copy(sensorQuaternion).invert();
    target.copy(facingRight);
    smoothed.copy(target);
    needsCalibration = false;
  } else {
    motion.copy(tiltAlignment)
      .multiply(sensorToScene)
      .multiply(sensorQuaternion)
      .multiply(neutralInverse)
      .multiply(sceneToSensor)
      .multiply(inverseTiltAlignment)
      .normalize();
    // Invertir solo las dos inclinaciones conserva el sentido del giro.
    motion.set(-motion.x, motion.y, -motion.z, motion.w);
    target.copy(facingRight).multiply(motion);
  }

  lastSampleTime = performance.now();
  stale = false;
  if (connected) showButtonState();
};

button.addEventListener('click', () => {
  if (!window.AndroidBle) {
    statusText = 'Bluetooth no disponible en esta vista';
    button.textContent = 'Bluetooth no disponible';
    button.disabled = true;
    return;
  }
  if (connected) {
    window.AndroidBle.disconnect();
  } else {
    needsCalibration = true;
    lastSampleTime = 0;
    window.AndroidBle.connect();
  }
});

function animate(now) {
  requestAnimationFrame(animate);
  const frameMs = Math.max(0, Math.min(100, now - lastFrameTime));
  lastFrameTime = now;
  smoothed.slerp(target, 1 - Math.exp(-frameMs / smoothingMs));
  if (model) {
    model.quaternion.copy(smoothed);
    renderer.render(scene, camera);
  }
  if (connected && lastSampleTime && !stale && now - lastSampleTime > 2500) {
    stale = true;
    statusText = 'Conectado sin nuevas muestras del sensor';
    showButtonState();
  }
}
requestAnimationFrame(animate);

window.addEventListener('resize', () => {
  camera.aspect = innerWidth / innerHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(innerWidth, innerHeight);
});

if (!window.AndroidBle) {
  button.textContent = 'Bluetooth no disponible';
  button.disabled = true;
}
