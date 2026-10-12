package net.sksat.ashidori

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import ashidori.core.TransportMode

class MainActivity extends Activity:
  private lazy val label = TextView(this)

  override def onCreate(savedInstanceState: Bundle): Unit =
    super.onCreate(savedInstanceState)
    label.setText(TransportMode.values.map(_.label).mkString(", "))
    setContentView(label)
