/*
 * Copyright (c) 2020-2022 Martin Denham, Tuomas Airaksinen and the AndBible contributors.
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

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.ContextMenu
import android.view.ContextMenu.ContextMenuInfo
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView.AdapterContextMenuInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog

import net.bible.android.activity.R
import net.bible.android.activity.databinding.ListBinding
import net.bible.android.control.event.ABEventBus
import net.bible.android.control.navigation.NavigationControl
import net.bible.android.control.readingplan.ReadingPlanControl
import net.bible.android.view.activity.base.Dialogs
import net.bible.android.view.activity.base.ListActivityBase
import net.bible.android.view.activity.installzip.InstallZip
import net.bible.android.view.activity.navigation.GridChoosePassageBook
import net.bible.service.db.ReadingPlansUpdatedViaSyncEvent
import net.bible.service.readingplan.ReadingPlanInfoDto
import org.crosswire.jsword.passage.VerseFactory
import org.crosswire.jsword.passage.VerseRange
import org.crosswire.jsword.versification.Versification
import org.crosswire.jsword.versification.system.Versifications

import javax.inject.Inject

/** do the search and show the search results
 *
 * @author Martin Denham [mjdenham at gmail dot com]
 */
class ReadingPlanSelectorList : ListActivityBase(R.menu.reading_plan_selector) {

    private lateinit var mReadingPlanList: List<ReadingPlanInfoDto>
    private lateinit var mPlanArrayAdapter: ArrayAdapter<ReadingPlanInfoDto>

    @Inject lateinit var readingPlanControl: ReadingPlanControl
    @Inject lateinit var navigationControl: NavigationControl

    private var pendingPlanName: String? = null
    private var pendingVersificationName: String? = null
    private var pendingBeginningVerseOsis: String? = null

    override val integrateWithHistoryManager: Boolean = true

    /** Called when the activity is first created.  */
    @SuppressLint("MissingSuperCall")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "Displaying Reading Plan List")
        val binding = ListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        buildActivityComponent().inject(this)
        try {
            mReadingPlanList = readingPlanControl.readingPlanList
            if (readingPlanControl.readingPlanUserDuplicates)
                Dialogs.showErrorMsg(getString(R.string.plan_duplicate_user_plan))

            mPlanArrayAdapter = ReadingPlanItemAdapter(this, LIST_ITEM_TYPE, mReadingPlanList)
            listAdapter = mPlanArrayAdapter

            registerForContextMenu(listView)
        } catch (e: Exception) {
            Log.e(TAG, "Error occurred analysing reading lists", e)
            Dialogs.showErrorMsg(R.string.error_occurred, e)
            finish()
        }

        isIntegrateWithHistoryManager = false
        ABEventBus.register(this)

        Log.i(TAG, "Finished displaying Reading Plan list")
    }

    fun onEventMainThread(e: ReadingPlansUpdatedViaSyncEvent) {
        recreate()
    }

    override fun onDestroy() {
        ABEventBus.unregister(this)
        super.onDestroy()
    }

    /** if a plan is selected then ask confirmation, save plan, and go straight to first day
     */
    override fun onListItemClick(l: ListView, v: View, position: Int, id: Long) {
        try {
            readingPlanControl.startReadingPlan(mReadingPlanList[position])

            setResult(RESULT_OK, Intent(mReadingPlanList[position].planCode))
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Plan selection error", e)
            Dialogs.showErrorMsg(R.string.error_occurred, e)
        }

    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo)
        val inflater = menuInflater
        inflater.inflate(R.menu.reading_plan_list_context_menu, menu)
    }


    override fun onContextItemSelected(item: MenuItem): Boolean {
        super.onContextItemSelected(item)
        val menuInfo = item.menuInfo as AdapterContextMenuInfo
        val plan = mReadingPlanList[menuInfo.position]
        Log.i(TAG, "Selected " + plan.planCode)
        when (item.itemId) {
			R.id.reset -> {
				readingPlanControl.reset(plan.planCode)
				return true
			}
		}
        return false
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.import_reading_plan -> {
                importPlanLauncher.launch("application/zip")
                true
            }
            R.id.create_reading_plan -> {
                showCreateReadingPlanDialog()
                true
            }
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showCreateReadingPlanDialog() {
        clearPendingCreation()
        val nameInput = EditText(this).apply {
            hint = getString(R.string.reading_plan_name)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.reading_plan_name)
            .setView(nameInput)
            .setPositiveButton(R.string.okay, null)
            .setNegativeButton(R.string.cancel) { _, _ -> clearPendingCreation() }
            .setOnCancelListener { clearPendingCreation() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val planName = nameInput.text.toString().trim()
                if (planName.isBlank()) {
                    nameInput.error = getString(R.string.reading_plan_name_required)
                    return@setOnClickListener
                }

                try {
                    val versificationName = navigationControl.versification.name
                    Versifications.instance().getVersification(versificationName)
                        ?: throw IllegalStateException("Unknown versification: $versificationName")
                    pendingPlanName = planName
                    pendingVersificationName = versificationName
                    dialog.dismiss()
                    launchBeginningVerseChooser()
                } catch (e: Exception) {
                    Log.e(TAG, "Could not capture reading plan versification", e)
                    clearPendingCreation()
                    dialog.dismiss()
                    Dialogs.showErrorMsg(R.string.reading_plan_versification_changed)
                }
            }
        }
        dialog.show()
    }

    private fun launchBeginningVerseChooser() {
        val intent = Intent(this, GridChoosePassageBook::class.java)
        intent.putExtra("isScripture", true)
        intent.putExtra("navigateToVerse", true)
        intent.putExtra("title", getString(R.string.speak_beginning_of_passage))
        beginningVerseLauncher.launch(intent)
    }

    private fun launchEndingVerseChooser() {
        val intent = Intent(this, GridChoosePassageBook::class.java)
        intent.putExtra("isScripture", true)
        intent.putExtra("navigateToVerse", true)
        intent.putExtra("title", getString(R.string.speak_ending_of_passage))
        endingVerseLauncher.launch(intent)
    }

    private fun capturedVersificationOrAbort(): Versification? {
        val versificationName = pendingVersificationName
        if (versificationName == null) {
            failCreation(IllegalStateException("Missing captured versification"))
            return null
        }
        return try {
            val versification = Versifications.instance().getVersification(versificationName)
                ?: throw IllegalStateException("Unknown versification: $versificationName")
            if (navigationControl.versification.name != versificationName) {
                abortForVersificationChange()
                null
            } else {
                versification
            }
        } catch (e: Exception) {
            Log.e(TAG, "Reading plan versification is no longer available", e)
            abortForVersificationChange()
            null
        }
    }

    private fun abortForVersificationChange() {
        clearPendingCreation()
        Dialogs.showErrorMsg(R.string.reading_plan_versification_changed)
    }

    private fun failCreation(error: Exception) {
        Log.e(TAG, "Reading plan creation failed", error)
        clearPendingCreation()
        Dialogs.showErrorMsg(R.string.error_occurred, error)
    }

    private fun clearPendingCreation() {
        pendingPlanName = null
        pendingVersificationName = null
        pendingBeginningVerseOsis = null
    }

    private val importPlanLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uriResult ->
        Log.i(TAG, "Importing plan. Result uri is${if (uriResult != null) " not" else ""} null")
        val uri = uriResult ?: return@registerForActivityResult

        val intent = Intent(Intent.ACTION_VIEW, uri, this, InstallZip::class.java)
        installZipLauncher.launch(intent)
    }

    private val installZipLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            // Refresh list so newly imported plans are immediately selectable
            recreate()
        }
    }

    private val beginningVerseLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            clearPendingCreation()
            return@registerForActivityResult
        }

        val verseOsis = result.data?.extras?.getString("verse")
        if (verseOsis == null) {
            failCreation(IllegalStateException("Missing beginning verse result"))
            return@registerForActivityResult
        }
        val versification = capturedVersificationOrAbort() ?: return@registerForActivityResult
        try {
            VerseFactory.fromString(versification, verseOsis)
            pendingBeginningVerseOsis = verseOsis
            launchEndingVerseChooser()
        } catch (e: Exception) {
            failCreation(e)
        }
    }

    private val endingVerseLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            clearPendingCreation()
            return@registerForActivityResult
        }

        val endingVerseOsis = result.data?.extras?.getString("verse")
        if (endingVerseOsis == null) {
            failCreation(IllegalStateException("Missing ending verse result"))
            return@registerForActivityResult
        }
        val planName = pendingPlanName
        if (planName == null) {
            failCreation(IllegalStateException("Missing reading plan name"))
            return@registerForActivityResult
        }
        val beginningVerseOsis = pendingBeginningVerseOsis
        if (beginningVerseOsis == null) {
            failCreation(IllegalStateException("Missing beginning verse"))
            return@registerForActivityResult
        }
        val versification = capturedVersificationOrAbort() ?: return@registerForActivityResult

        try {
            val beginningVerse = VerseFactory.fromString(versification, beginningVerseOsis)
            val endingVerse = VerseFactory.fromString(versification, endingVerseOsis)
            if (endingVerse.ordinal < beginningVerse.ordinal) {
                Dialogs.showErrorMsg(R.string.reading_plan_ending_before_beginning) {
                    if (capturedVersificationOrAbort() != null) launchEndingVerseChooser()
                }
                return@registerForActivityResult
            }

            readingPlanControl.createSessionPlan(
                planName,
                VerseRange(versification, beginningVerse, endingVerse)
            )
            clearPendingCreation()
        } catch (e: Exception) {
            failCreation(e)
        }
    }

    companion object {
        private const val TAG = "ReadingPlanList"

        private const val LIST_ITEM_TYPE = android.R.layout.simple_list_item_2
    }
}
