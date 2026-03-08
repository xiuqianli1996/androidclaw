package ai.androidclaw.ui.widget

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val matrixValues = FloatArray(9)
    private val imageMatrixInternal = Matrix()
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())

    private var lastX = 0f
    private var lastY = 0f
    private var isDragging = false

    init {
        scaleType = ScaleType.MATRIX
        imageMatrix = imageMatrixInternal
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (!isDragging) {
                        isDragging = dx * dx + dy * dy > 16f
                    }
                    if (isDragging) {
                        imageMatrixInternal.postTranslate(dx, dy)
                        imageMatrix = imageMatrixInternal
                        lastX = event.x
                        lastY = event.y
                    }
                }
            }
        }
        return true
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scale = detector.scaleFactor.coerceIn(0.8f, 1.25f)
            imageMatrixInternal.getValues(matrixValues)
            val currentScale = matrixValues[Matrix.MSCALE_X]
            val targetScale = (currentScale * scale).coerceIn(1f, 5f)
            val realScale = targetScale / currentScale

            imageMatrixInternal.postScale(realScale, realScale, detector.focusX, detector.focusY)
            imageMatrix = imageMatrixInternal
            return true
        }
    }
}
