/*
 * Copyright (c) 2026 Sykerö Software / Tuomas Airaksinen and the AndBible contributors.
 *
 * This file is part of AndBible: Bible Study (http://github.com/AndBible/and-bible).
 *
 * AndBible is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * AndBible is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with AndBible.
 * If not, see http://www.gnu.org/licenses/.
 */

package net.bible.android.view.activity.readingplan

import android.app.AlertDialog
import android.content.res.Configuration
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.Toast
import net.bible.android.activity.R
import net.bible.android.activity.databinding.CustomReadingPlanDetailActivityBinding
import net.bible.android.view.activity.base.ActivityBase

private const val STATE_DRAFT_PLAN = "draft_plan"
private const val MIN_MINUTES = 5
private const val MAX_MINUTES = 60
private const val MIN_PERIOD_DAYS = 1
private const val MAX_PERIOD_DAYS = 7

class CustomReadingPlanDetailActivity : ActivityBase() {
    private lateinit var binding: CustomReadingPlanDetailActivityBinding
    private lateinit var draftPlan: CustomReadingPlan
    private var isNewPlan = false
    private var bindingState = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = CustomReadingPlanDetailActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        isNewPlan = intent.getBooleanExtra(EXTRA_IS_NEW_PLAN, false)
        draftPlan = savedInstanceState?.getSerializable(STATE_DRAFT_PLAN) as? CustomReadingPlan
            ?: loadInitialPlan()

        setupViews()
        bindDraftToViews()
        renderDraft()
    }


    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        recreate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable(STATE_DRAFT_PLAN, draftPlan)
    }

    override fun onBackPressed() {
        confirmEditing()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            confirmEditing()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun loadInitialPlan(): CustomReadingPlan {
        val planId = intent.getStringExtra(EXTRA_PLAN_ID)
        return if (planId != null) {
            CustomReadingPlanInMemoryRepository.getPlan(planId)
                ?: defaultPlan()
        } else {
            defaultPlan()
        }
    }

    private fun defaultPlan(): CustomReadingPlan = CustomReadingPlan(
        title = getString(R.string.custom_reading_plan_new_title),
        selectionSummary = defaultSelectionSummary(this),
    )

    private fun setupViews() = binding.apply {
        titleInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (bindingState) return
                draftPlan = draftPlan.copy(
                    title = s?.toString().orEmpty().ifBlank { getString(R.string.custom_reading_plan_new_title) }
                )
                renderDraft()
            }
        })

        selectedBooksValue.setOnClickListener {
            startActivity(Intent(this@CustomReadingPlanDetailActivity, CustomReadingPlanSelectionPlaceholderActivity::class.java).apply {
                putExtra(CustomReadingPlanSelectionPlaceholderActivity.EXTRA_PLAN_TITLE, draftPlan.title)
                putExtra(CustomReadingPlanSelectionPlaceholderActivity.EXTRA_SELECTION_SUMMARY, draftPlan.selectionSummary)
            })
        }

        minutesSlider.max = MAX_MINUTES - MIN_MINUTES
        periodSlider.max = MAX_PERIOD_DAYS - MIN_PERIOD_DAYS
        minutesSlider.setOnSeekBarChangeListener(createSeekBarListener { progress ->
            draftPlan = draftPlan.copy(minutesPerSession = progress + MIN_MINUTES)
            renderDraft()
        })
        periodSlider.setOnSeekBarChangeListener(createSeekBarListener { progress ->
            draftPlan = draftPlan.copy(periodInDays = progress + MIN_PERIOD_DAYS)
            renderDraft()
        })

        weekDayCheckboxes().forEach { (day, checkbox) ->
            checkbox.setOnCheckedChangeListener { _, isChecked ->
                if (bindingState) return@setOnCheckedChangeListener
                val updatedDays = LinkedHashSet(draftPlan.selectedDays)
                if (isChecked) {
                    updatedDays += day
                } else {
                    updatedDays -= day
                }
                draftPlan = draftPlan.copy(selectedDays = updatedDays)
                renderDraft()
            }
        }

        confirmButton.setOnClickListener { confirmEditing() }
        cancelButton.setOnClickListener { cancelEditing() }
        deleteButton.setOnClickListener { confirmDelete() }
    }

    private fun bindDraftToViews() = binding.apply {
        bindingState = true
        titleInput.setText(draftPlan.title)
        titleInput.setSelection(titleInput.text?.length ?: 0)
        minutesSlider.progress = draftPlan.minutesPerSession - MIN_MINUTES
        periodSlider.progress = draftPlan.periodInDays - MIN_PERIOD_DAYS
        weekDayCheckboxes().forEach { (day, checkbox) ->
            checkbox.isChecked = day in draftPlan.selectedDays
        }
        bindingState = false
    }

    private fun renderDraft() = binding.apply {
        supportActionBar?.title = if (isNewPlan) {
            getString(R.string.custom_reading_plan_detail_create_title)
        } else {
            draftPlan.title
        }

        val estimatedPages = CustomReadingPlanUiFormatter.estimatePages(draftPlan.minutesPerSession)
        titleReadingTime.text = getString(R.string.custom_reading_plan_minutes_label_with_value, draftPlan.minutesPerSession)
        selectedBooksValue.text = draftPlan.selectionSummary
        minutesSummary.text = getString(R.string.custom_reading_plan_minutes_summary, draftPlan.minutesPerSession, estimatedPages)
        periodSummary.text = CustomReadingPlanUiFormatter.periodLabel(this@CustomReadingPlanDetailActivity, draftPlan.periodInDays)
        futureReadingSpeedSettingValue.text = getString(R.string.custom_reading_plan_reading_speed_setting_placeholder, FUTURE_READING_SPEED_SETTING_KEY)

        val hasSelectedDays = draftPlan.selectedDays.isNotEmpty()

        val (completionInfo, progressInfo) = CustomReadingPlanUiFormatter.buildAdditionalInfo(
            this@CustomReadingPlanDetailActivity,
            draftPlan,
        )
        additionalInfoPrimary.text = completionInfo
        additionalInfoSecondary.text = progressInfo
        confirmButton.isEnabled = hasSelectedDays
        deleteButton.isEnabled = !isNewPlan
    }

    private fun createSeekBarListener(onProgressChanged: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (!fromUser || bindingState) return
            onProgressChanged(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun weekDayCheckboxes(): List<Pair<ReadingWeekDay, CheckBox>> = listOf(
        ReadingWeekDay.MONDAY to binding.checkboxMonday,
        ReadingWeekDay.TUESDAY to binding.checkboxTuesday,
        ReadingWeekDay.WEDNESDAY to binding.checkboxWednesday,
        ReadingWeekDay.THURSDAY to binding.checkboxThursday,
        ReadingWeekDay.FRIDAY to binding.checkboxFriday,
        ReadingWeekDay.SATURDAY to binding.checkboxSaturday,
        ReadingWeekDay.SUNDAY to binding.checkboxSunday,
    )

    private fun confirmEditing() {
        if (draftPlan.selectedDays.isEmpty()) {
            Toast.makeText(this, R.string.custom_reading_plan_select_weekday_error, Toast.LENGTH_SHORT).show()
            return
        }

        CustomReadingPlanInMemoryRepository.upsert(draftPlan)
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_RESULT_ACTION, RESULT_ACTION_SAVED)
            putExtra(EXTRA_RESULT_PLAN_ID, draftPlan.id)
        })
        finish()
    }

    private fun cancelEditing() {
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun confirmDelete() {
        if (isNewPlan) return

        AlertDialog.Builder(this)
            .setMessage(getString(R.string.custom_reading_plan_delete_confirmation, draftPlan.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                CustomReadingPlanInMemoryRepository.delete(draftPlan.id)
                setResult(RESULT_OK, Intent().apply {
                    putExtra(EXTRA_RESULT_ACTION, RESULT_ACTION_DELETED)
                    putExtra(EXTRA_RESULT_PLAN_ID, draftPlan.id)
                })
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_PLAN_ID = "plan_id"
        const val EXTRA_IS_NEW_PLAN = "is_new_plan"
        const val EXTRA_RESULT_ACTION = "result_action"
        const val EXTRA_RESULT_PLAN_ID = "result_plan_id"
        const val RESULT_ACTION_SAVED = "saved"
        const val RESULT_ACTION_DELETED = "deleted"
    }
}
