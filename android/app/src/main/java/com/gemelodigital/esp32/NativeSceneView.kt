package com.gemelodigital.esp32

import android.content.Context
import android.graphics.Rect
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Skybox
import com.google.android.filament.ToneMapper
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.SceneView
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlin.math.atan
import kotlin.math.tan

/** The only bridge between the former Three.js world and Filament's scene graph. */
private object SceneCoordinates {
    // Both worlds are right-handed, +Y up, and a front-facing camera looks along -Z.
    // The old model was driven by q in world space. Therefore the basis change is I*q*I^-1 = q.
    fun threeToFilament(x: Float, y: Float, z: Float, w: Float) = Quaternion(x, y, z, w)
}

class NativeSceneView(context: Context) : SceneView(
    context = context,
    isOpaque = true,
    cameraManipulator = null
) {
    fun interface FrameListener { fun onFrame(timeMillis: Long) }
    private var frameListener: FrameListener? = null
    private val modelPivot = Node(engine)
    private val focusBounds = Rect()
    private val viewerPreferences = context.getSharedPreferences("viewer", Context.MODE_PRIVATE)
    private var zoom = readSavedZoom()
    private lateinit var axes: ModelNode

    init {
        // Three.js used ACES filmic output with exposure 1.15. Filament's exposure is in stops.
        view.colorGrading = ColorGrading.Builder()
            .toneMapper(ToneMapper.ACES())
            .exposure(0.20f)
            .build(engine)
        skybox = Skybox.Builder().color(floatArrayOf(0.00402f, 0.00802f, 0.01764f, 1f)).build(engine)

        cameraNode.position = Position(0f, 1.65f, 2.8f)
        cameraNode.lookAt(Position(0f, 0.95f, 0f))
        // Softer direct and ambient light keep the color atlas and skull details visible.
        indirectLight?.let { it.intensity *= 0.5f }
        mainLightNode?.apply {
            lightDirection = Direction(-4f / 10.77f, -8f / 10.77f, -6f / 10.77f)
            intensity = 40_000f
            isShadowCaster = false
        }

        modelPivot.position = Position(0f, 1f, 0f)
        modelPivot.isTouchable = false
        addChildNode(modelPivot)

        // ModelLoader resolves the GLB and its embedded materials/textures once.
        val instance = modelLoader.createModelInstance("cubone.glb")
        val model = ModelNode(instance, autoAnimate = false)
        val center = model.boundingBox.center
        val half = model.boundingBox.halfExtent
        val largest = 2f * maxOf(half[0], half[1], half[2])
        val fit = if (largest > 0f) 1.8f / largest else 1f
        model.scale = Scale(fit)
        model.position = Position(-center[0] * fit, -center[1] * fit, -center[2] * fit)
        model.isTouchable = false
        modelPivot.addChildNode(model)

        // Static counterpart of the former GridHelper and LineLoop; it never follows IMU motion.
        val guide = ModelNode(modelLoader.createModelInstance("scene-guide.glb"), autoAnimate = false)
        guide.isTouchable = false
        addChildNode(guide)

        axes = ModelNode(modelLoader.createModelInstance("model-axes.glb"), autoAnimate = false)
        axes.isTouchable = false
        axes.isVisible = false
        modelPivot.addChildNode(axes)

        onFrame = { timeNanos -> frameListener?.onFrame(timeNanos / 1_000_000L) }
    }

    override fun onResized(width: Int, height: Int) {
        super.onResized(width, height)
        updateFraming(width, height)
    }

    /** Overlay bounds define the usable scene area; the SurfaceView remains full screen. */
    fun setFocusBounds(left: Int, top: Int, right: Int, bottom: Int) {
        focusBounds.set(left, top, right, bottom)
        updateFraming(width, height)
    }

    fun zoomBy(delta: Float) {
        if (delta.isFinite()) setZoom((zoom + delta).coerceIn(0.65f, 1.5f))
    }

    fun resetZoom() {
        setZoom(1f)
    }

    private fun readSavedZoom(): Float {
        val stored = try { viewerPreferences.getFloat("zoom.v1", 1f) }
        catch (_: ClassCastException) { 1f }
        return if (stored.isFinite()) stored.coerceIn(0.65f, 1.5f) else 1f
    }

    private fun setZoom(value: Float) {
        zoom = value
        // Persist camera zoom only. Calibration, model scale and IMU motion are independent.
        viewerPreferences.edit().putFloat("zoom.v1", zoom).apply()
        updateFraming(width, height)
    }

    fun setAxesVisible(visible: Boolean) { axes.isVisible = visible }

    private fun updateFraming(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val bounds = if (focusBounds.isEmpty) Rect(0, 0, width, height) else focusBounds
        val safeWidth = bounds.width().coerceAtLeast(1).toDouble()
        val safeHeight = bounds.height().coerceAtLeast(1).toDouble()
        // Fit the shorter side of the usable area. Camera/pivot/model scale and the
        // IMU quaternion stay identical in portrait, landscape, expanded view and zoom.
        val safeTan = tan(Math.toRadians(48.0) / 2.0) / zoom * maxOf(1.0, safeHeight / safeWidth)
        val verticalFov = Math.toDegrees(2.0 * atan(safeTan * height / safeHeight))
        cameraNode.setProjection(verticalFov, 0.1f, 100f, Camera.Fov.VERTICAL, width.toDouble() / height)
        // Column-major OpenGL projection: shift the optical center into the free
        // area above the status cards without translating or rotating the model.
        val projection = DoubleArray(16)
        cameraNode.camera.getCullingProjectionMatrix(projection)
        projection[8] = 1.0 - 2.0 * bounds.exactCenterX() / width
        projection[9] = 2.0 * bounds.exactCenterY() / height - 1.0
        cameraNode.camera.setCustomProjection(projection, 0.1, 100.0)
    }

    fun setFrameListener(listener: FrameListener?) { frameListener = listener }

    fun applyOrientation(x: Float, y: Float, z: Float, w: Float) {
        modelPivot.quaternion = SceneCoordinates.threeToFilament(x, y, z, w)
    }
}
