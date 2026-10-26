package com.test.fakeyt;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Inicio de la YouTube de prueba: lista de videos, sin reproducir nada. */
public class HomeActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 80, 40, 40);

        TextView t = new TextView(this);
        t.setText("Inicio (YouTube de prueba)");
        t.setTextSize(20);
        root.addView(t);

        Button play = new Button(this);
        play.setText("Video de prueba");
        play.setOnClickListener(v -> startActivity(new Intent(this, PlayerActivity.class)));
        root.addView(play);

        // Señuelo: un "Más opciones" que la app NO debe tocar.
        ImageButton decoy = new ImageButton(this);
        decoy.setImageResource(android.R.drawable.ic_menu_more);
        decoy.setContentDescription("Más opciones");
        decoy.setOnClickListener(v -> Log.i("FAKEYT", "FAKEYT decoy home"));
        root.addView(decoy);

        setContentView(root);
    }
}
