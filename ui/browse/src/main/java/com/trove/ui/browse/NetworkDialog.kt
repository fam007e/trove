package com.trove.ui.browse

import android.app.AlertDialog
import android.view.View
import android.content.Context
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.trove.core.fs.FileItem
import com.trove.core.net.NetworkConnectionFactory
import kotlinx.coroutines.launch

/**
 * "Add network place" dialog (MiX-style): protocol spinner, host, port,
 * user, password, path. Connects through NetworkConnectionFactory and hands
 * the resulting root to [onConnected]. Remembers connections in Prefs so a
 * host can be re-opened without retyping. Passwords live in app-private
 * SharedPreferences (same trade-off MiX makes) — encrypt at rest is a
 * follow-up with the vault.
 */
class NetworkDialog(
    private val context: Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val onConnected: (NetworkConnectionFactory.ConnectionSpec, FileItem) -> Unit
) {

    fun show() {
        val protocols = NetworkConnectionFactory.Protocol.entries
        val density = context.resources.displayMetrics.density

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        fun label(text: String): TextView = TextView(context).apply { this.text = text }
        fun field(hint: String, value: String = "", password: Boolean = false): EditText =
            EditText(context).apply {
                this.hint = hint
                setText(value)
                setSingleLine(true)
                if (password) {
                    transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
                }
            }

        box.addView(label("Protocol"))
        val typeSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context, android.R.layout.simple_spinner_dropdown_item,
                protocols.map { it.label }
            )
        }
        box.addView(typeSpinner)

        val hostField = field("host")
        val portField = field("port", protocols[0].defaultPort.toString())
        val userField = field("user", "anonymous")
        val passField = field("password", "", password = true)
        val pathField = field("path / share (optional)")

        box.addView(hostField)
        box.addView(portField)
        box.addView(userField)
        box.addView(passField)
        box.addView(label("Path / share"))
        box.addView(pathField)

        val saved = Prefs.savedConnections().toList()
        if (saved.isNotEmpty()) {
            box.addView(label("Saved connections"))
            val savedList = ListView(context)
            savedList.adapter = object : BaseAdapter() {
                override fun getCount(): Int = saved.size
                override fun getItem(position: Int): Any = saved[position]
                override fun getItemId(position: Int): Long = position.toLong()
                override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): View {
                    val tv = (convertView as? TextView) ?: TextView(context)
                    tv.text = saved[position].split("|").take(4).joinToString("  ·  ")
                    tv.setPadding(8, 16, 8, 16)
                    return tv
                }
            }
            savedList.setOnItemClickListener { _, _, p, _ ->
                val parts = saved[p].split("|")
                val protoIdx = protocols.indexOfFirst { it.name == parts[0] }.coerceAtLeast(0)
                typeSpinner.setSelection(protoIdx)
                hostField.setText(parts[1])
                portField.setText(parts[2])
                userField.setText(parts[3])
                passField.setText(parts[4])
                pathField.setText(parts.getOrElse(5) { "" })
            }
            box.addView(savedList, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (40 * density).toInt() * saved.size.coerceAtMost(3)
            ))
        }

        AlertDialog.Builder(context)
            .setTitle("Add network place")
            .setView(box)
            .setPositiveButton("Connect") { _, _ ->
                val protocol = protocols[typeSpinner.selectedItemPosition]
                val host = hostField.text.toString().trim()
                val port = portField.text.toString().trim().toIntOrNull() ?: protocol.defaultPort
                if (host.isEmpty()) {
                    Toast.makeText(context, "Host required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val spec = NetworkConnectionFactory.ConnectionSpec(
                    protocol = protocol,
                    host = host,
                    port = port,
                    user = userField.text.toString().trim(),
                    password = passField.text.toString(),
                    path = pathField.text.toString().trim()
                )
                Toast.makeText(context, "Connecting to ${spec.host}:${spec.port}…", Toast.LENGTH_SHORT).show()
                scope.launch {
                    when (val res = NetworkConnectionFactory.connect(spec)) {
                        is com.trove.core.fs.Result.Success -> {
                            Prefs.addSavedConnection(spec)
                            onConnected(spec, res.value)
                        }
                        is com.trove.core.fs.Result.Failure -> Toast.makeText(
                            context, "Connect failed: ${res.exception.message}", Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
