package com.prash.petcare.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prash.petcare.R
import com.prash.petcare.databinding.ItemAppointmentBinding
import com.prash.petcare.ui.common.formatAppointmentTime

/**
 * Draws the dashboard's "Upcoming appointments" card: the next few vet visits across every pet.
 *
 * Read-only, and so the simplest adapter in the app — no click handling and no state to keep in
 * step. An appointment appears here so the user knows it is coming; changing one means opening the
 * routine that schedules it, which is a deliberate journey rather than something to trip over from
 * the dashboard.
 */
class AppointmentAdapter :
    ListAdapter<UpcomingAppointment, AppointmentAdapter.AppointmentViewHolder>(
        AppointmentDiffCallback
    ) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppointmentViewHolder {
        val binding = ItemAppointmentBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return AppointmentViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppointmentViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /** Holds the views for one row. */
    class AppointmentViewHolder(
        private val binding: ItemAppointmentBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(appointment: UpcomingAppointment) {
            val context = binding.root.context

            binding.appointmentTitleText.text = appointment.task.title

            // Which pet, and when — the two facts that make an appointment actionable. The moment
            // is formatted by the device, so it arrives already translated and in the user's own
            // 12- or 24-hour convention; see ui.common.formatAppointmentTime.
            binding.appointmentSubtitleText.text = context.getString(
                R.string.home_appointment_subtitle,
                appointment.petName,
                formatAppointmentTime(context, appointment.dueAtMillis)
            )
        }
    }

    /**
     * Tells [ListAdapter] how to compare two appointment lists.
     *
     * Identity is the task id. The same vet visit keeps its place in the list as its due date rolls
     * forward — which it does the moment one occurrence passes — rather than appearing to be
     * removed and replaced by a different appointment.
     */
    private object AppointmentDiffCallback : DiffUtil.ItemCallback<UpcomingAppointment>() {

        override fun areItemsTheSame(
            oldItem: UpcomingAppointment,
            newItem: UpcomingAppointment
        ): Boolean = oldItem.task.id == newItem.task.id

        override fun areContentsTheSame(
            oldItem: UpcomingAppointment,
            newItem: UpcomingAppointment
        ): Boolean = oldItem == newItem
    }
}
