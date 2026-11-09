package com.operit.lshare;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText(R.string.hint);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(16);
        setContentView(tv);
    }
}
