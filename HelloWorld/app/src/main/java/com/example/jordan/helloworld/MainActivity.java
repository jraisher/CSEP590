package com.example.jordan.helloworld;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

public class MainActivity extends AppCompatActivity {

    private boolean hello = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    public void handleMyButtonClick(View view) {
        Button b = (Button) this.findViewById(R.id.button);
        hello = !hello;

        if (hello) {
            b.setText("Goodbye World");
        } else {
            b.setText("Hello world");
        }

    }
}
