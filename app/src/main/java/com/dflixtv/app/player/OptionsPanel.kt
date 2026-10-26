package com.dflixtv.app.player

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dflixtv.app.ui.isTv

class PanelOption(val label: String, val selected: Boolean = false, val onPick: () -> Unit)

class PanelColumn(val title: String, val options: List<PanelOption>, val weight: Float = 1f)

/**
 * Netflix-style "Audio & Subtitles" overlay: titled columns over the dimmed video, the current
 * choice marked with a check. Works with D-pad (arrows move between and within columns) and touch.
 * Picking an option closes the panel, then runs it.
 */
fun showOptionsPanel(context: Context, columns: List<PanelColumn>, focusColumn: Int = 0): Dialog {
    val tv = context.isTv()
    // Many columns: tighter text and gaps so labels aren't cut off.
    val dense = columns.count { it.options.isNotEmpty() } > 4
    val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(if (tv) (if (dense) 40 else 64) else 24), dp(if (tv) 56 else 20), dp(if (tv) 24 else 12), dp(24))
        // Tapping empty space closes the panel, like Netflix on phones.
        isClickable = true
        setOnClickListener { dialog.dismiss() }
    }
    var initialFocus: View? = null
    var initialFocusColumn = -1

    columns.forEachIndexed { columnIndex, column ->
        if (column.options.isEmpty()) return@forEachIndexed
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        list.addView(TextView(context).apply {
            text = column.title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tv) (if (dense) 21f else 24f) else 17f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            setPadding(dp(12), 0, dp(12), dp(14))
        })
        column.options.forEach { option ->
            val item = TextView(context).apply {
                text = if (option.selected) "✓  ${option.label}" else option.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tv) (if (dense) 17f else 19f) else 15f)
                setTextColor(itemTextColors(option.selected))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                typeface = if (option.selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                // Unselected labels line up with the text after the check mark.
                setPadding(dp(if (option.selected) 12 else 34), dp(10), dp(12), dp(10))
                background = itemBackground(dp(6).toFloat())
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    dialog.dismiss()
                    option.onPick()
                }
            }
            list.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // Start on the current choice in focusColumn (subtitles), the most common change.
            if (option.selected && (initialFocus == null || columnIndex == focusColumn)) {
                if (initialFocus == null || initialFocusColumn != focusColumn) {
                    initialFocus = item
                    initialFocusColumn = columnIndex
                }
            }
        }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }
        row.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, column.weight).apply {
            marginEnd = dp(if (tv) (if (dense) 12 else 24) else 8)
        })
    }

    dialog.setContentView(row)
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(0xE6000000.toInt()))
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
    }
    dialog.setOnShowListener { initialFocus?.requestFocus() }
    dialog.show()
    return dialog
}

private fun itemTextColors(selected: Boolean) = android.content.res.ColorStateList(
    arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
    intArrayOf(Color.BLACK, if (selected) Color.WHITE else 0xFFB3B3B3.toInt()),
)

/** White pill behind the focused item, nothing otherwise. */
private fun itemBackground(radius: Float) = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadius = radius
    })
    addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
        setColor(0x33FFFFFF)
        cornerRadius = radius
    })
    addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
}
