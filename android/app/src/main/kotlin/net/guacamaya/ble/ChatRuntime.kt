package net.guacamaya.ble

import android.content.Context
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.guacamaya.crypto.Identity
import net.guacamaya.crypto.Signer
import net.guacamaya.mesh.ChatFragmentEntity
import net.guacamaya.mesh.ChatMessageEntity
import net.guacamaya.mesh.GuacamayaDatabase
import net.guacamaya.proto.ChatCodec
import net.guacamaya.proto.ChatMessage
import net.guacamaya.time.MeshClock

object ChatRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rng = SecureRandom()
    private var app: Context? = null
    private var broadcaster: ChatBroadcaster? = null
    private var identity: Identity? = null
    private val relayed = LinkedHashSet<String>()
    private val _policy = MutableStateFlow(RadioPolicyEngine.forTier(RadioTier.NORMAL))
    val policy: StateFlow<RadioPolicy> = _policy
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active
    @Volatile private var windowClosed: (() -> Unit)? = null

    fun initialize(context: Context) {
        if (app != null) return
        app = context.applicationContext
        broadcaster = ChatBroadcaster.create(context)
        scope.launch { identity = Identity.loadOrCreate(context) }
    }

    fun setPolicy(policy: RadioPolicy) { _policy.value = policy }
    fun setWindowClosedListener(listener: (() -> Unit)?) { windowClosed = listener }

    fun openActiveWindow(explicitSend: Boolean = false) {
        if (_policy.value.tier == RadioTier.CRITICAL && !explicitSend) return
        _active.value = true
        scope.launch { delay(30_000L); _active.value = false; broadcaster?.stop(); windowClosed?.invoke() }
    }

    fun send(text: String): Result<Unit> {
        val bytes = text.trim().toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) return Result.failure(IllegalArgumentException("Message is empty"))
        if (bytes.size > ChatCodec.MAX_TEXT_BYTES) return Result.failure(IllegalArgumentException("Message exceeds 160 UTF-8 bytes"))
        val context = app ?: return Result.failure(IllegalStateException("Chat is not initialized"))
        scope.launch {
            val id = identity ?: Identity.loadOrCreate(context).also { identity = it }
            val messageId = rng.nextLong()
            val timestamp = MeshClock.now(context) / 1000
            val canonical = ChatCodec.canonical(id.nodeId, messageId, timestamp, bytes)
            val message = ChatMessage(id.nodeId, messageId, timestamp, bytes, id.publicKey, Signer.signMessage(id.privateKeySeed, canonical))
            val dao = GuacamayaDatabase.get(context).chatDao()
            dao.insertChatMessage(message.toEntity(own = true, state = 0, receivedAt = System.currentTimeMillis()))
            openActiveWindow(explicitSend = true)
            air(message, ttl = 3)
            dao.updateChatDelivery(id.nodeId.hex(), messageId, 1)
            prune(dao)
        }
        return Result.success(Unit)
    }

    fun onFrame(frame: ByteArray) {
        val context = app ?: return
        val fragment = ChatCodec.decode(frame) ?: return
        scope.launch {
            val dao = GuacamayaDatabase.get(context).chatDao()
            val sender = fragment.senderNodeId.hex()
            val ownId = identity?.nodeId?.hex()
            if (sender == ownId) {
                dao.updateChatDelivery(sender, fragment.messageId, 2)
                return@launch
            }
            dao.insertChatFragment(
                ChatFragmentEntity(sender, fragment.messageId, fragment.index, fragment.count, fragment.totalLength,
                    fragment.timestampSeconds, fragment.chunk, fragment.publicKey, fragment.signature, fragment.ttl, System.currentTimeMillis()),
            )
            val parts = dao.chatFragments(sender, fragment.messageId)
            if (parts.size != fragment.count || parts.map { it.fragmentIndex }.distinct().size != fragment.count) return@launch
            val text = parts.sortedBy { it.fragmentIndex }.flatMap { it.chunk.asIterable() }.toByteArray()
            if (text.size != fragment.totalLength) return@launch
            val message = ChatMessage(fragment.senderNodeId, fragment.messageId, fragment.timestampSeconds, text, fragment.publicKey, fragment.signature)
            if (!ChatCodec.verify(message, MeshClock.now(context) / 1000)) {
                dao.deleteChatFragments(sender, fragment.messageId)
                return@launch
            }
            dao.insertChatMessage(message.toEntity(false, 2, System.currentTimeMillis()))
            dao.deleteChatFragments(sender, fragment.messageId)
            val key = "$sender:${fragment.messageId}"
            if (fragment.ttl > 0 && _policy.value.relayEnabled && synchronized(relayed) { relayed.add(key) }) {
                delay(300L + rng.nextInt(901))
                air(message, fragment.ttl - 1)
            }
            prune(dao)
        }
    }

    private suspend fun air(message: ChatMessage, ttl: Int) {
        val frames = ChatCodec.fragment(message, ttl)
        repeat(3) {
            for (frame in frames) {
                broadcaster?.advertise(frame, _policy.value.txPower, active = _active.value)
                delay(650L)
            }
        }
        broadcaster?.stop()
    }

    private suspend fun prune(dao: net.guacamaya.mesh.ChatDao) {
        val now = System.currentTimeMillis()
        dao.pruneChatFragments(now - 15 * 60_000L)
        dao.pruneChatMessages(now - 24 * 60 * 60_000L)
    }

    private fun ChatMessage.toEntity(own: Boolean, state: Int, receivedAt: Long) = ChatMessageEntity(
        senderNodeId.hex(), messageId, timestampSeconds * 1000, text.toString(Charsets.UTF_8), publicKey, signature, own, state, receivedAt,
    )

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
