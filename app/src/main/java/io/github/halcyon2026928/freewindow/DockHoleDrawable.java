package io.github.halcyon2026928.freewindow;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.List;

/**
 * 坞窗面板背景，带若干个可移动的「洞」。
 *
 * 卡片走真实小窗对齐时，卡片矩形里必须是透明的：坞窗窗口 type 2520 在应用窗口之上，
 * 面板背景（原来那个 0xE5 圆角矩形）会把下面的真窗口压暗。这里把卡片区从面板背景里挖掉，
 * 真窗口就从洞里透出来；洞按卡片自己那圈圆角挖，边缘和面板底色无缝，看不出挖过洞。
 *
 * 多卡片时每张卡一个洞，洞用面板局部坐标。
 */
final class DockHoleDrawable extends Drawable {

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final RectF mTmp = new RectF();
    private final float[] mRadii;
    private final List<Rect> mHoles = new ArrayList<>();
    private float mHoleRadius;

    DockHoleDrawable(int color, float[] radii) {
        mPaint.setColor(color);
        mPaint.setStyle(Paint.Style.FILL);
        mRadii = radii;
    }

    /** 洞的圆角半径（px），和卡片内容区的圆角保持一致。 */
    void setHoleRadius(float radius) {
        mHoleRadius = radius;
        invalidateSelf();
    }

    void setHole(Rect hole) {
        mHoles.clear();
        if (hole != null && !hole.isEmpty()) mHoles.add(new Rect(hole));
        invalidateSelf();
    }

    void setHoles(List<Rect> holes) {
        mHoles.clear();
        if (holes != null) {
            for (Rect hole : holes) {
                if (hole != null && !hole.isEmpty()) mHoles.add(new Rect(hole));
            }
        }
        invalidateSelf();
    }

    int holeCount() {
        return mHoles.size();
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        mPath.reset();
        mTmp.set(b);
        mPath.addRoundRect(mTmp, mRadii, Path.Direction.CW);
        for (Rect hole : mHoles) {
            mTmp.set(hole);
            if (mHoleRadius > 0f) {
                mPath.addRoundRect(mTmp, mHoleRadius, mHoleRadius, Path.Direction.CCW);
            } else {
                mPath.addRect(mTmp, Path.Direction.CCW);
            }
        }
        if (!mHoles.isEmpty()) {
            mPath.setFillType(Path.FillType.EVEN_ODD);
        }
        canvas.drawPath(mPath, mPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
