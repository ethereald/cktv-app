package com.cktv.android;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

final class TvUi {
    static final int BACKGROUND = Color.rgb(9, 13, 23);
    static final int SURFACE = Color.rgb(21, 27, 43);
    static final int ACCENT = Color.rgb(88, 214, 199);
    static final int MUTED = Color.rgb(170, 179, 197);

    private TvUi() {}

    static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    static TextView text(Context context, String value, float size, int color) {
        TextView view = new TextView(context);
        view.setText(value == null ? "" : value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    static Button button(Context context, String label) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setMinHeight(dp(context, 46));
        button.setPadding(dp(context, 14), 0, dp(context, 14), 0);
        button.setBackground(background(SURFACE, dp(context, 10)));
        button.setOnFocusChangeListener((view, focused) -> {
            button.setBackground(background(focused ? ACCENT : SURFACE, dp(context, 10)));
            button.setTextColor(focused ? BACKGROUND : Color.WHITE);
            button.setScaleX(focused ? 1.04f : 1f);
            button.setScaleY(focused ? 1.04f : 1f);
        });
        return button;
    }

    static GradientDrawable background(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    static void focusOn(View view) {
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.requestFocus();
    }
}
