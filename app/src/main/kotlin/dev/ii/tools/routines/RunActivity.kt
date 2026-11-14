package dev.ii.tools.routines

import android.app.Activity
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** A shortcut's target (see the manifest): runs the PC action in `a` and leaves at once. */
class RunActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = PcAction.of(intent.getStringExtra("a"))
        val ctx = applicationContext
        if (action != null) CoroutineScope(Dispatchers.Default).launch { Routines.exec(ctx, action) }
        finish()
    }
}
