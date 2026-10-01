package com.sshborg

import android.content.Intent
import com.sshborg.data.db.HostDao
import com.sshborg.data.db.HostEntity

/**
 * A request from Booter (com.rodolfo.booter) to open an EC2 instance it lists: the instance
 * ID, its Name tag, and the public IP it has right now.
 *
 * Any app can send this intent, so it never connects anywhere on its own say-so. It only
 * opens a host the user already saved, linked to that instance ID, and that host keeps its
 * pinned host key: a request that points it at some other server meets a host-key warning.
 * An instance with no saved host opens the editor, prefilled, and nothing is stored until
 * the user saves it.
 */
data class Ec2Launch(val instanceId: String, val name: String, val host: String) {

    sealed interface Target {
        /** A saved host for this instance, already updated to the current address. */
        data class Saved(val host: HostEntity) : Target
        /** No saved host yet: open the editor prefilled with this request. */
        data class New(val launch: Ec2Launch) : Target
    }

    /**
     * Finds the host for this instance: the one linked to its ID, or else an unlinked host
     * already pointing at its current address, which gets linked now. A linked host whose
     * address changed is moved to the new one, carrying its pinned host key along.
     */
    suspend fun resolve(dao: HostDao): Target {
        val linked = dao.getByEc2InstanceId(instanceId)
        if (linked != null) {
            if (linked.hostname == host) return Target.Saved(linked)
            val moved = linked.copy(
                hostname = host,
                knownHostsEntry = linked.knownHostsEntry?.let { rehost(it, host) },
            )
            dao.upsert(moved)
            return Target.Saved(moved)
        }
        val sameAddress = dao.getUnlinkedByHostname(host)
        if (sameAddress != null) {
            val adopted = sameAddress.copy(ec2InstanceId = instanceId)
            dao.upsert(adopted)
            return Target.Saved(adopted)
        }
        return Target.New(this)
    }

    companion object {
        const val ACTION = "com.sshborg.action.OPEN_EC2_INSTANCE"
        const val EXTRA_INSTANCE_ID = "com.sshborg.extra.EC2_INSTANCE_ID"
        const val EXTRA_NAME = "com.sshborg.extra.EC2_NAME"
        const val EXTRA_HOST = "com.sshborg.extra.EC2_HOST"

        private val INSTANCE_ID = Regex("^i-[0-9a-f]{8,17}$")
        /** An IPv4/IPv6 address or a DNS name: nothing that could smuggle in options or spaces. */
        private val HOST = Regex("^[A-Za-z0-9.:-]{1,253}$")

        /** The request carried by [intent], or null when it isn't one or doesn't look right. */
        fun from(intent: Intent?): Ec2Launch? {
            if (intent?.action != ACTION) return null
            val id = intent.getStringExtra(EXTRA_INSTANCE_ID)?.trim() ?: return null
            val host = intent.getStringExtra(EXTRA_HOST)?.trim() ?: return null
            if (!INSTANCE_ID.matches(id) || !HOST.matches(host) || host.startsWith("-")) return null
            val name = intent.getStringExtra(EXTRA_NAME)?.trim()?.take(100)?.takeIf { it.isNotEmpty() } ?: id
            return Ec2Launch(id, name, host)
        }

        /**
         * [line] ("host type key", as SshManager.buildKnownHostsLine writes it) re-pointed at
         * [newHost]. An instance keeps its host key across stops and starts, so the pin stays
         * valid at the new address; only the host field, "[host]:port" off port 22, changes.
         */
        private fun rehost(line: String, newHost: String): String {
            val hostField = line.substringBefore(' ')
            val rest = line.substringAfter(' ', "")
            if (rest.isEmpty()) return line
            val newField = if (hostField.startsWith("[")) "[$newHost]${hostField.substringAfter(']')}" else newHost
            return "$newField $rest"
        }
    }
}
