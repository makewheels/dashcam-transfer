package com.makewheels.dashcam;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

/** Small native UI palette with consistent touch targets, spacing and hierarchy. */
final class Ui {
  static final int INK = 0xff16253c,
      MUTED = 0xff6b788c,
      BLUE = 0xff2563eb,
      BG = 0xfff5f7fb,
      LINE = 0xffe5eaf2,
      TINT = 0xffedf3ff,
      GREEN = 0xff148765,
      AMBER = 0xffad6612;
  final Context context;

  Ui(Context context) {
    this.context = context;
  }

  int dp(int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  GradientDrawable shape(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  TextView text(String value, int size, int color) {
    TextView t = new TextView(context);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setIncludeFontPadding(false);
    t.setLineSpacing(dp(3), 1);
    return t;
  }

  TextView bold(String value, int size, int color) {
    TextView t = text(value, size, color);
    t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    return t;
  }

  LinearLayout column() {
    LinearLayout l = new LinearLayout(context);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  LinearLayout row() {
    LinearLayout l = new LinearLayout(context);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  LinearLayout card() {
    LinearLayout l = column();
    l.setPadding(dp(20), dp(20), dp(20), dp(20));
    l.setBackground(shape(Color.WHITE, 20));
    return l;
  }

  void add(LinearLayout parent, View child, int top) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.topMargin = dp(top);
    parent.addView(child, p);
  }

  void line(LinearLayout parent, int top) {
    View v = new View(context);
    v.setBackgroundColor(LINE);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(1));
    p.topMargin = dp(top);
    p.bottomMargin = dp(top);
    parent.addView(v, p);
  }

  TextView button(String label, boolean primary, Runnable action) {
    TextView b = bold(label, 15, primary ? Color.WHITE : BLUE);
    b.setGravity(Gravity.CENTER);
    b.setMinHeight(dp(50));
    b.setPadding(dp(16), dp(12), dp(16), dp(12));
    b.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(0x202563eb), shape(primary ? BLUE : TINT, 14), null));
    b.setOnClickListener(v -> action.run());
    b.setFocusable(true);
    return b;
  }

  ProgressBar bar() {
    ProgressBar p = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
    p.setMax(1000);
    p.setProgressTintList(ColorStateList.valueOf(BLUE));
    p.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(8));
    lp.topMargin = dp(10);
    p.setLayoutParams(lp);
    return p;
  }

  TextView chip(String label, int color, int background) {
    TextView t = bold(label, 11, color);
    t.setPadding(dp(9), dp(5), dp(9), dp(5));
    t.setBackground(shape(background, 8));
    return t;
  }

  ImageView icon(String name, int color, int size) {
    ImageView i = new ImageView(context);
    i.setImageDrawable(new Icon(name, color));
    i.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
    i.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return i;
  }

  static final class Icon extends Drawable {
    final String name;
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    Icon(String name, int color) {
      this.name = name;
      paint.setColor(color);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(1.7f);
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public void draw(Canvas c) {
      c.save();
      c.translate(getBounds().left, getBounds().top);
      c.scale(getBounds().width() / 24f, getBounds().height() / 24f);
      Path p = new Path();
      switch (name) {
        case "history":
          c.drawCircle(12, 12, 9, paint);
          p.moveTo(12, 6);
          p.lineTo(12, 12);
          p.lineTo(16, 14);
          break;
        case "start":
          p.moveTo(3, 10);
          p.lineTo(12, 3);
          p.lineTo(21, 10);
          p.moveTo(5, 9);
          p.lineTo(5, 21);
          p.lineTo(19, 21);
          p.lineTo(19, 9);
          p.moveTo(10, 21);
          p.lineTo(10, 14);
          p.lineTo(14, 14);
          p.lineTo(14, 21);
          break;
        case "upload":
          p.moveTo(4, 17);
          p.lineTo(4, 21);
          p.lineTo(20, 21);
          p.lineTo(20, 17);
          p.moveTo(12, 17);
          p.lineTo(12, 3);
          p.moveTo(7, 8);
          p.lineTo(12, 3);
          p.lineTo(17, 8);
          break;
        case "cloud":
          p.moveTo(7, 19);
          p.cubicTo(0, 19, 1, 9, 7, 10);
          p.cubicTo(7, 1, 21, 3, 19, 11);
          p.cubicTo(25, 12, 23, 19, 18, 19);
          p.close();
          break;
        case "settings":
          for (int x : new int[] {5, 12, 19}) {
            p.moveTo(x, 3);
            p.lineTo(x, 21);
          }
          c.drawPath(p, paint);
          p.reset();
          c.drawCircle(5, 8, 2.5f, paint);
          c.drawCircle(12, 16, 2.5f, paint);
          c.drawCircle(19, 10, 2.5f, paint);
          break;
        case "folder":
          p.moveTo(3, 6);
          p.lineTo(10, 6);
          p.lineTo(12, 9);
          p.lineTo(21, 9);
          p.lineTo(21, 20);
          p.lineTo(3, 20);
          p.close();
          break;
        case "check":
          p.moveTo(5, 12);
          p.lineTo(10, 17);
          p.lineTo(19, 7);
          break;
        case "wifi":
          p.moveTo(3, 8);
          p.quadTo(12, 1, 21, 8);
          p.moveTo(6, 12);
          p.quadTo(12, 7, 18, 12);
          p.moveTo(9, 16);
          p.quadTo(12, 13, 15, 16);
          c.drawCircle(12, 20, .8f, paint);
          break;
        case "cell":
          p.moveTo(5, 20);
          p.lineTo(5, 15);
          p.moveTo(10, 20);
          p.lineTo(10, 11);
          p.moveTo(15, 20);
          p.lineTo(15, 7);
          p.moveTo(20, 20);
          p.lineTo(20, 3);
          break;
        case "video":
          c.drawRoundRect(3, 3, 21, 21, 4, 4, paint);
          p.moveTo(10, 8);
          p.lineTo(16, 12);
          p.lineTo(10, 16);
          p.close();
          break;
        case "trash":
          p.moveTo(4, 6);
          p.lineTo(20, 6);
          p.moveTo(9, 3);
          p.lineTo(15, 3);
          p.moveTo(6, 6);
          p.lineTo(7, 21);
          p.lineTo(17, 21);
          p.lineTo(18, 6);
          p.moveTo(10, 10);
          p.lineTo(10, 17);
          p.moveTo(14, 10);
          p.lineTo(14, 17);
          break;
        default:
          c.drawCircle(12, 12, 9, paint);
      }
      c.drawPath(p, paint);
      c.restore();
    }

    @Override
    public void setAlpha(int alpha) {
      paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
      paint.setColorFilter(filter);
    }

    @Override
    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }
}
