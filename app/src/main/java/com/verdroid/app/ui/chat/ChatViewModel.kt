package com.verdroid.app.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdroid.app.chathead.ChatHeadController
import com.verdroid.app.chathead.ChatHeadResult
import com.verdroid.app.domain.model.ChatMessage
import com.verdroid.app.domain.model.PauseReason
import com.verdroid.app.domain.model.Task
import com.verdroid.app.domain.model.ToolCallRecord
import com.verdroid.app.domain.repository.AgentController
import com.verdroid.app.domain.repository.TaskRepository
import com.verdroid.app.data.storage.ChatAttachment
import com.verdroid.app.data.storage.SharedFolder
import com.verdroid.app.domain.usecase.SendMessageUseCase
import com.verdroid.app.domain.usecase.StartConversationUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val tasks: TaskRepository,
    private val agent: AgentController,
    private val startConversation: StartConversationUseCase,
    private val sendMessage: SendMessageUseCase,
    val chatHeads: ChatHeadController,
    private val sharedFolder: SharedFolder,
    private val presets: com.verdroid.app.data.tools.ChatToolPresets,
    private val modelPins: com.verdroid.app.data.settings.ChatModelPinStore,
    private val settingsRepo: com.verdroid.app.domain.repository.SettingsRepository,
    private val toolPrefs: com.verdroid.app.data.tools.ToolPrefs,
    private val toolRegistry: com.verdroid.app.agent.tools.ToolRegistry,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {
    /** 0 = a brand-new conversation that is created on the first send. */
    private val taskId = MutableStateFlow(savedState.get<Long>("taskId") ?: 0L)

    /**
     * The real chat id (0 until the first send creates the task). The nav route of a new chat stays "chat/0", so the
     * screen publishes THIS to [com.verdroid.app.chathead.VisibleChat], never the route argument.
     */
    val currentTaskId: StateFlow<Long> = taskId.asStateFlow()

    val task: StateFlow<Task?> = taskId.flatMapLatest { if (it == 0L) flowOf(null) else tasks.observeTask(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    /** How many newest messages the UI holds (v1.0.56: windowed; scrolling to the top loads [ChatWindow.PAGE] more). */
    val window = MutableStateFlow(com.verdroid.app.domain.model.ChatWindow.PAGE)
    private val idAndWindow = combine(taskId, window) { id, w -> id to w }
    val messages: StateFlow<List<ChatMessage>> = idAndWindow.flatMapLatest { (id, w) -> if (id == 0L) flowOf(emptyList()) else tasks.observeMessageWindow(id, w) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val toolCalls: StateFlow<List<ToolCallRecord>> = idAndWindow.flatMapLatest { (id, w) -> if (id == 0L) flowOf(emptyList()) else tasks.observeToolCallWindow(id, w) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun loadEarlier() { if (com.verdroid.app.domain.model.ChatWindow.hasMore(messages.value.size, window.value)) window.value += com.verdroid.app.domain.model.ChatWindow.PAGE }
    val isGenerating: StateFlow<Boolean> = combine(taskId, agent.runningTaskIds) { id, running -> id in running }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Model pinned on a brand-new chat (taskId 0), saved when the first message creates it. */
    private val draftPin = MutableStateFlow<com.verdroid.app.data.settings.ModelPin?>(null)

    /** This chat's pinned model (null = Settings → Model priority, the default). */
    val modelPin: StateFlow<com.verdroid.app.data.settings.ModelPin?> =
        taskId.flatMapLatest { if (it == 0L) draftPin else modelPins.observe(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Global Model priority (offered in the chat's Model picker). */
    val globalModels: StateFlow<List<String>> = settingsRepo.modelPriority
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setModelPin(pin: com.verdroid.app.data.settings.ModelPin?) {
        val id = taskId.value
        if (id == 0L) draftPin.value = pin else modelPins.set(id, pin)
    }

    /** Overrides picked on a brand-new chat (taskId 0), saved to the chat when the first message creates it. */
    private val draftPresets = MutableStateFlow<Map<com.verdroid.app.data.tools.ToolPreset, Boolean>>(emptyMap())

    /** This chat's per-group overrides (empty = follows Settings → Tools, the default). */
    val presetOverrides: StateFlow<Map<com.verdroid.app.data.tools.ToolPreset, Boolean>> =
        taskId.flatMapLatest { if (it == 0L) draftPresets else presets.observe(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Settings → Tools state per preset (live; drives the dialog switches; effective = override, else Settings). */
    val presetsGlobal: StateFlow<Map<com.verdroid.app.data.tools.ToolPreset, com.verdroid.app.data.tools.ToolPreset.Companion.Global>> =
        toolPrefs.disabled.map { com.verdroid.app.data.tools.ToolPreset.globalState(toolRegistry.tools.map { t -> t.name }, it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private fun updatePresets(f: (Map<com.verdroid.app.data.tools.ToolPreset, Boolean>) -> Map<com.verdroid.app.data.tools.ToolPreset, Boolean>) {
        val id = taskId.value
        if (id == 0L) draftPresets.update(f) else presets.set(id, f(presets.overrides(id)))
    }

    fun setPreset(preset: com.verdroid.app.data.tools.ToolPreset, enabled: Boolean) =
        updatePresets { com.verdroid.app.data.tools.ToolPreset.toggle(it, preset, enabled, presetsGlobal.value) }

    fun setAllPresets(enabled: Boolean) =
        updatePresets { com.verdroid.app.data.tools.ToolPreset.toggleAll(it, enabled, presetsGlobal.value) }

    private val _pendingAttach = MutableStateFlow<ChatAttachment.Saved?>(null)
    val pendingAttach: StateFlow<ChatAttachment.Saved?> = _pendingAttach.asStateFlow()
    private val _attachError = MutableStateFlow<String?>(null)
    val attachError: StateFlow<String?> = _attachError.asStateFlow()

    /** A pick that's waiting for All files access; retried by [retryAttachAfterAccess]. */
    private val _attachNeedsAccess = MutableStateFlow<Uri?>(null)
    val attachNeedsAccess: StateFlow<Uri?> = _attachNeedsAccess.asStateFlow()

    fun clearAttachError() { _attachError.value = null; _attachNeedsAccess.value = null }
    fun clearPendingAttach() { _pendingAttach.value = null }

    /** Every "+" pick is copied into Documents/Verdroid/Input and kept until the next send. */
    fun attach(uri: Uri) {
        viewModelScope.launch {
            _attachError.value = null
            _attachNeedsAccess.value = null
            val saved = runCatching {
                withContext(Dispatchers.IO) { ChatAttachment.saveToInput(appContext, sharedFolder, uri) }
            }.getOrElse {
                if (it is ChatAttachment.NoAccessException) _attachNeedsAccess.value = uri
                _attachError.value = it.message ?: "Could not attach file"
                return@launch
            }
            _pendingAttach.value = saved
        }
    }

    /** Back from the All files access screen: copy the waiting pick into Input/ now (keeps the prompt if still denied). */
    fun retryAttachAfterAccess() {
        val uri = _attachNeedsAccess.value ?: return
        if (sharedFolder.hasAccess()) attach(uri)
    }

    fun send(text: String) {
        val pending = _pendingAttach.value
        val t = text.trim()
        if (t.isEmpty() && pending == null) return
        if (task.value?.archived == true) return
        val body = if (pending != null) ChatAttachment.messagePrefix(pending) + t.ifEmpty { ChatAttachment.DEFAULT_PROMPT } else t
        viewModelScope.launch {
            val id = taskId.value
            if (id == 0L) {
                val draft = draftPresets.value
                val pinDraft = draftPin.value
                // Bind the new id BEFORE the agent starts, so the chat head (Home → auto head, or the head button)
                // already points at this chat during its first in-flight turn, not at "chat 0".
                val newId = startConversation(body) { newId ->
                    if (draft.isNotEmpty()) presets.set(newId, draft)
                    pinDraft?.let { modelPins.set(newId, it) }
                    taskId.value = newId
                }
                taskId.value = newId
                draftPresets.value = emptyMap()
                draftPin.value = null
            } else sendMessage(id, body)
            _pendingAttach.value = null
        }
    }

    /** v1.0.12: bring an archived chat back to the home list. */
    fun restore() { taskId.value.takeIf { it != 0L }?.let { viewModelScope.launch { tasks.restore(it) } } }

    fun stop() { taskId.value.takeIf { it != 0L }?.let(agent::stop) }
    fun resume() = forceRetry()
    /** Phase 3: drop the scheduled backoff/resume and run now. */
    fun forceRetry() { taskId.value.takeIf { it != 0L }?.let(agent::forceRetry) }
    /** Phase 3: cancel the task and its scheduled retries. */
    fun cancelTask() { taskId.value.takeIf { it != 0L }?.let(agent::cancel) }
    /** Opens the current conversation as a bubble or overlay chat head (null for an unsaved new chat). */
    suspend fun openChatHead(): ChatHeadResult? = taskId.value.takeIf { it != 0L }?.let { chatHeads.open(it) }

    fun markOpened() { taskId.value.takeIf { it != 0L }?.let { viewModelScope.launch { tasks.markOpened(it) } } }
}
