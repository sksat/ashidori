package net.sksat.ashidori.ui

import android.view.View

/** One page of the app. Tab roots and pushed detail pages share this. */
trait Screen:
  def view: View
  /** Title shown in the top app bar (pushed screens). */
  def title: String = ""
  def onShow(): Unit = ()
  def onHide(): Unit = ()
  def onDestroy(): Unit = ()
