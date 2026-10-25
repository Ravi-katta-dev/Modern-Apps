package com.vayunmathur.health.platform

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.vayunmathur.health.data.DoseEvent
import com.vayunmathur.health.data.MedicalKind
import com.vayunmathur.health.data.MedicationEntry
import com.vayunmathur.health.data.MedicationSchedule
import com.vayunmathur.health.data.MedicationStatus
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.uuid.Uuid

fun MedicalViewModel.saveMedicationDraft() {
    val draft = medicationDraftState.value
    if (draft.ingredient.isBlank()) return
    viewModelScope.launch {
        val existing = draft.editingId?.let { repository.getMedication(it) }
        val detached = repository.detachIfForeign(
            MedicalKind.Medication,
            PersonalHealthRecords.medicationRequestResourceType,
            existing?.dataSourceId,
            existing?.fhirResourceId,
        )
        val active = draft.status == MedicationStatus.Active
        val edited = (existing ?: MedicationEntry(
            id = Uuid.random().toString(),
            displayName = "",
            startedAt = Instant.now(),
        )).copy(
            rxcui = draft.rxcui,
            displayName = draft.ingredient.trim(),
            strength = draft.strength,
            doseForm = draft.doseForm,
            status = draft.status,
            startedAt = draft.startedOn.toInstant(),
            // The end field is hidden while Active, so a date left over from before would
            // otherwise be saved without the user seeing it.
            endedAt = if (active) null else draft.endedOn?.toInstant(),
            dosageText = draft.dosage.blankToNull(),
            note = draft.note.blankToNull(),
        )
        val entry = if (detached) edited.copy(fhirResourceId = null, dataSourceId = null) else edited
        repository.upsertMedication(entry)
        saveSchedule(draft, entry.id)
        repository.markPending(MedicalKind.Medication, entry.id)
        repository.mirrorMedication(entry.id)
    }
}

/**
 * Writes or clears the reminder schedule for [medicationId] and re-arms the alarm.
 *
 * Always re-arms from the stored row rather than the draft, so what fires is what was saved. A
 * medication that is no longer being taken keeps its schedule but switched off, so it stops ringing.
 */
private suspend fun MedicalViewModel.saveSchedule(
    draft: MedicalViewModel.MedicationDraft,
    medicationId: String,
) {
    val context: Context = getApplication()
    if (!draft.remindersEnabled || draft.times.isEmpty()) {
        draft.scheduleId?.let { DoseScheduler.cancel(context, it) }
        repository.deleteScheduleFor(medicationId)
        return
    }
    val schedule = MedicationSchedule(
        id = draft.scheduleId ?: Uuid.random().toString(),
        medicationId = medicationId,
        enabled = draft.status == MedicationStatus.Active,
        times = draft.times.sorted(),
        repeatUnit = draft.repeatUnit,
        interval = draft.interval.coerceAtLeast(1),
        daysOfWeek = draft.daysOfWeek,
        anchorDate = draft.anchorDate,
        endDate = draft.remindersUntil,
    )
    repository.upsertSchedule(schedule)
    DoseScheduler.arm(context, schedule)
}

fun MedicalViewModel.deleteMedication(entry: MedicationEntry) {
    viewModelScope.launch {
        repository.deleteMedicationEverywhere(getApplication(), entry)
    }
}

/** Records that a dose was taken, now. Called from the medication list. */
fun MedicalViewModel.recordDoseTaken(medicationId: String, takenAt: Instant = Instant.now()) {
    viewModelScope.launch { repository.recordDose(medicationId, takenAt) }
}

fun MedicalViewModel.deleteDoseEvent(event: DoseEvent) {
    viewModelScope.launch {
        repository.deleteDoseEvent(event)
        repository.forgetRow(
            MedicalKind.Dose,
            event.id,
            PersonalHealthRecords.medicationStatementResourceType,
            event.dataSourceId,
            event.fhirResourceId,
        )
    }
}
