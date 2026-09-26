package com.gemelodigital.esp32

import android.content.Context
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

    init {
        // Three.js used ACES filmic output with exposure 1.15. Filament's exposure is in stops.
        view.colorGrading = ColorGrading.Builder()
            .toneMapper(ToneMapper.ACES())
            .exposure(0.20f)
            .build(engine)
        skybox = Skybox.Builder().color(floatArrayOf(0.00402f, 0.00802f, 0.01764f, 1f)).build(engine)

        cameraNode.position = Position(0f, 1.65f, 2.8f)
        cameraNode.lookAt(Position(0f, 0.95f, 0f))
        mainLightNode?.apply {
            lightDirection = Direction(-4f / 10.77f, -8f / 10.77f, -6f / 10.77f)
            intensity = 80_000f
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

        onFrame = { timeNanos -> frameListener?.onFrame(timeNanos / 1_000_000L) }
    }

    override fun onResized(width: Int, height: Int) {
        super.onResized(width, height)
        if (width > 0 && height > 0) {
            // Keep the same framing along the viewport's shorter side. A fixed vertical
            // FOV narrows the horizontal view in portrait fullscreen and crops Cubone.
            // Changing only the projection preserves model scale, pivot and IMU rotation.
            val fovAxis = if (width < height) Camera.Fov.HORIZONTAL else Camera.Fov.VERTICAL
            cameraNode.setProjection(48.0, 0.1f, 100f, fovAxis, width.toDouble() / height)
        }
    }

    fun setFrameListener(listener: FrameListener?) { frameListener = listener }

    fun applyOrientation(x: Float, y: Float, z: Float, w: Float) {
        modelPivot.quaternion = SceneCoordinates.threeToFilament(x, y, z, w)
    }
}
