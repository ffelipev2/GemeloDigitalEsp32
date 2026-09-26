package com.gemelodigital.esp32;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Native strokes matching the seven small SVG icons in the former dashboard. */
final class NativeIcon extends View {
    enum Shape { CUBE, TARGET, BLUETOOTH, RESET, EXPAND, ORIENTATION, SETTINGS, AXES }
    private final Shape shape;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    NativeIcon(Context context, Shape shape, int color) {
        super(context);
        this.shape = shape;
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.9f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int save = canvas.save();
        float size = Math.min(getWidth(), getHeight());
        canvas.translate((getWidth()-size)/2f,(getHeight()-size)/2f);
        canvas.scale(size/24f,size/24f);
        switch (shape) {
            case CUBE:
                line(canvas,12,2,3.5f,6.8f,3.5f,17.2f,12,22,20.5f,17.2f,20.5f,6.8f,12,2);
                line(canvas,3.5f,6.8f,12,11.7f,20.5f,6.8f);
                line(canvas,12,11.7f,12,22); break;
            case TARGET:
                canvas.drawCircle(12,12,8,paint); canvas.drawCircle(12,12,3,paint);
                line(canvas,12,1,12,5); line(canvas,12,19,12,23);
                line(canvas,1,12,5,12); line(canvas,19,12,23,12); break;
            case BLUETOOTH:
                line(canvas,7,7.5f,17,16,12,21,12,3,17,8,7,16.5f); break;
            case RESET:
                line(canvas,4,3,4,8,9,8);
                path.reset(); path.moveTo(4.5f,8); path.cubicTo(6,4,10,3,13,3.5f);
                path.cubicTo(18,4,21,8,21,12.5f); path.cubicTo(21,17,17.5f,21,12.5f,21);
                path.cubicTo(8,21,5,18,4,15); canvas.drawPath(path,paint); break;
            case EXPAND:
                line(canvas,8,3,3,3,3,8); line(canvas,16,3,21,3,21,8);
                line(canvas,3,16,3,21,8,21); line(canvas,21,16,21,21,16,21); break;
            case ORIENTATION:
                canvas.drawCircle(12,12,7,paint);
                line(canvas,12,1,12,6); line(canvas,12,18,12,23);
                line(canvas,1,12,6,12); line(canvas,18,12,23,12);
                line(canvas,7,12,9,5,12,7,15,5,17,12,15,19,12,17,9,19,7,12); break;
            case SETTINGS:
                canvas.drawCircle(12,12,7,paint); canvas.drawCircle(12,12,2.5f,paint);
                line(canvas,12,2,12,4); line(canvas,12,20,12,22);
                line(canvas,2,12,4,12); line(canvas,20,12,22,12);
                line(canvas,4.9f,4.9f,6.3f,6.3f); line(canvas,17.7f,17.7f,19.1f,19.1f);
                line(canvas,4.9f,19.1f,6.3f,17.7f); line(canvas,17.7f,6.3f,19.1f,4.9f); break;
            case AXES:
                line(canvas,12,12,12,3); line(canvas,12,12,3.5f,19); line(canvas,12,12,20.5f,19);
                canvas.drawCircle(12,12,2.3f,paint); canvas.drawCircle(12,3,1.4f,paint);
                canvas.drawCircle(3.5f,19,1.4f,paint); canvas.drawCircle(20.5f,19,1.4f,paint); break;
        }
        canvas.restoreToCount(save);
    }

    private void line(Canvas canvas, float... points) {
        path.reset(); path.moveTo(points[0],points[1]);
        for (int i=2;i<points.length;i+=2) path.lineTo(points[i],points[i+1]);
        canvas.drawPath(path,paint);
    }
}
