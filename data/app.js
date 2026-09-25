// --- Escena básica ---
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
scene.add(gridHelper);

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

const mountingOffset = new THREE.Quaternion();
mountingOffset.setFromEuler(new THREE.Euler(-Math.PI / 2, 0, -Math.PI / 2));

let dynamicOffset = new THREE.Quaternion();
dynamicOffset.identity();

let lastSensorQuat = null;

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

// --- Conexión SSE ---
const evtSource = new EventSource('/events');
evtSource.addEventListener('quat', (event) => {
  const data = JSON.parse(event.data);
  const sensorQuat = new THREE.Quaternion(data.x, data.y, data.z, data.w).normalize();
  const firstSample = !lastSensorQuat;
  lastSensorQuat = sensorQuat;

  targetQuaternion.copy(dynamicOffset).multiply(mountingOffset).multiply(sensorQuat);
  if (firstSample) smoothQuaternion.copy(targetQuaternion);
  if (!sensorOnline && modelToRotate) {
    document.getElementById('estado').textContent = 'Cubone y sensor conectados';
  }
  sensorOnline = true;
});
evtSource.addEventListener('error', () => {
  sensorOnline = false;
  if (modelToRotate) {
    document.getElementById('estado').textContent = 'Conexión perdida; reconectando...';
  }
});

// --- Animación principal ---
function animate(now) {
  requestAnimationFrame(animate);
  const frameMs = Math.min(100, now - lastFrameTime);
  lastFrameTime = now;
  smoothQuaternion.slerp(targetQuaternion, 1 - Math.exp(-frameMs / smoothingMs));

  if (modelToRotate) {
    modelToRotate.quaternion.copy(smoothQuaternion).multiply(manualQuaternion);
  }

  renderer.render(scene, camera);
}
requestAnimationFrame(animate);

// --- UI ---
document.querySelectorAll('input[type="number"]').forEach(input => {
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

// --- Botón copiar valores ---
document.getElementById('anotar').addEventListener('click', () => {
  const fx = parseFloat(document.getElementById('rotX').value).toFixed(4);
  const fy = parseFloat(document.getElementById('rotY').value).toFixed(4);
  const fz = parseFloat(document.getElementById('rotZ').value).toFixed(4);
  alert(`Valores copiados:\nX: ${fx}\nY: ${fy}\nZ: ${fz}`);
});

// --- Botón calibrar ---
document.getElementById('calibrar').addEventListener('click', () => {
  if (lastSensorQuat) {
    const currentTotalQuat = mountingOffset.clone().multiply(lastSensorQuat);
    const inverseTotal = currentTotalQuat.clone().invert();
    dynamicOffset.copy(inverseTotal);
    console.log("✅ Calibración aplicada correctamente.");
  } else {
    console.log("Esperando datos del sensor para calibrar...");
  }
});

// --- Redimensionar ventana ---
window.addEventListener('resize', () => {
  camera.aspect = window.innerWidth / window.innerHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(window.innerWidth, window.innerHeight);
});
