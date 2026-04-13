package org.sajith.agentcli.plugin.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import org.sajith.agentcli.plugin.AgentType
import org.sajith.agentcli.plugin.session.AgentCliSession
import org.sajith.agentcli.plugin.session.ClaudeCodeHistoryReader
import org.sajith.agentcli.plugin.session.CodexHistoryReader
import org.sajith.agentcli.plugin.session.CursorHistoryReader
import org.sajith.agentcli.plugin.session.GeminiHistoryReader
import org.sajith.agentcli.plugin.session.HistoricalSession
import org.sajith.agentcli.plugin.session.SessionManager
import org.sajith.agentcli.plugin.settings.AgentCliSettings
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.border.MatteBorder

/**
 * Sidebar items are either a date group header or a history entry.
 */
private sealed class SidebarItem {
    data class Header(val title: String) : SidebarItem()

    data class HistoryEntry(val session: HistoricalSession) : SidebarItem()
}

class SessionSidebarPanel(
    private val project: Project,
    private val onNewSession: (agentType: AgentType) -> Unit,
    private val onSessionSelected: (AgentCliSession) -> Unit,
    private val onSessionClosed: (AgentCliSession) -> Unit,
    private val onSessionDeleted: (AgentCliSession) -> Unit,
    private val onResumeSession: (agentType: AgentType, sessionId: String, title: String?) -> Unit,
    private val onHistorySessionDeleted: (HistoricalSession) -> Unit,
) : JPanel(BorderLayout()) {
    private val activeSessionListModel = DefaultListModel<AgentCliSession>()
    private val activeSessionList = JBList(activeSessionListModel)
    private var selectedSession: AgentCliSession? = null

    private val historyListModel = DefaultListModel<SidebarItem>()
    private val historyList = JBList(historyListModel)

    private val historyCardLayout = CardLayout()
    private val historyCardPanel = JPanel(historyCardLayout)
    private var loadingTimer: Timer? = null

    private val sessionListPanel: JPanel
    private var isCollapsed = false
    private var historyLoaded = false

    var onCollapseToggle: ((collapsed: Boolean) -> Unit)? = null

    init {
        LOG.info("[AgentCLI] SessionSidebarPanel init START")
        background = JBColor.PanelBackground

        add(createIconStrip(), BorderLayout.WEST)

        sessionListPanel =
            JPanel(BorderLayout()).apply {
                background = JBColor.PanelBackground
                border = MatteBorder(0, 0, 0, 1, JBColor.border())
                add(createActiveSessionList(), BorderLayout.NORTH)
                add(createHistoryList(), BorderLayout.CENTER)
            }
        add(sessionListPanel, BorderLayout.CENTER)

        // Defer history loading until the panel is actually shown
        addHierarchyListener {
            if (isShowing && !historyLoaded) {
                LOG.info("[AgentCLI] HierarchyListener triggered — panel is now showing, loading history")
                historyLoaded = true
                loadHistory()
            }
        }
        LOG.info("[AgentCLI] SessionSidebarPanel init END")
    }

    private fun createIconStrip(): JComponent {
        val group =
            DefaultActionGroup().apply {
                add(
                    object : AnAction("New Session", "Create a new session", AllIcons.General.Add) {
                        override fun actionPerformed(e: AnActionEvent) {
                            showAgentTypePopup(e) { agentType -> onNewSession(agentType) }
                        }
                    },
                )
                add(
                    object : AnAction("Refresh History", "Reload session history", AllIcons.Actions.Refresh) {
                        override fun actionPerformed(e: AnActionEvent) {
                            loadHistory()
                        }
                    },
                )
                add(
                    object : AnAction(
                        "Toggle Sessions Panel",
                        "Show or hide the sessions panel",
                        AllIcons.Actions.ArrowCollapse,
                    ) {
                        override fun actionPerformed(e: AnActionEvent) {
                            isCollapsed = !isCollapsed
                            sessionListPanel.isVisible = !isCollapsed
                            e.presentation.icon =
                                if (isCollapsed) AllIcons.Actions.ArrowExpand else AllIcons.Actions.ArrowCollapse
                            onCollapseToggle?.invoke(isCollapsed)
                        }
                    },
                )
            }
        val toolbar =
            ActionManager.getInstance()
                .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, false)
        toolbar.targetComponent = this
        toolbar.component.border = MatteBorder(0, 0, 0, 1, JBColor.border())
        return toolbar.component
    }

    private fun showAgentTypePopup(
        e: AnActionEvent,
        onSelected: (AgentType) -> Unit,
    ) {
        val enabledAgents = AgentCliSettings.getInstance().enabledAgentTypes
        val group =
            DefaultActionGroup().apply {
                enabledAgents.forEach { agentType ->
                    add(
                        object : AnAction(agentType.displayName) {
                            override fun actionPerformed(e: AnActionEvent) {
                                onSelected(agentType)
                            }
                        },
                    )
                }
            }
        val component = e.inputEvent?.component ?: this
        val popup =
            JBPopupFactory.getInstance().createActionGroupPopup(
                null,
                group,
                DataManager.getInstance().getDataContext(component),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                false,
            )
        popup.showUnderneathOf(component)
    }

    // ── Active sessions list (top, compact) ──

    private fun createActiveSessionList(): JComponent {
        activeSessionList.apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = ActiveSessionCellRenderer()
            background = JBColor.PanelBackground
            minimumSize = Dimension(0, 0)
            addListSelectionListener { e ->
                if (!e.valueIsAdjusting) {
                    activeSessionList.selectedValue?.let { session ->
                        selectedSession = session
                        historyList.clearSelection()
                        onSessionSelected(session)
                    }
                }
            }
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (SwingUtilities.isMiddleMouseButton(e)) {
                            val index = activeSessionList.locationToIndex(e.point)
                            if (index >= 0) {
                                val cellBounds = activeSessionList.getCellBounds(index, index)
                                if (cellBounds != null && cellBounds.contains(e.point)) {
                                    onSessionClosed(activeSessionListModel.getElementAt(index))
                                }
                            }
                        }
                        if (SwingUtilities.isLeftMouseButton(e)) {
                            val index = activeSessionList.locationToIndex(e.point)
                            if (index < 0) return
                            val cellBounds = activeSessionList.getCellBounds(index, index) ?: return
                            if (!cellBounds.contains(e.point)) return
                            // Close button is on the right edge (~16px)
                            val relativeX = e.x - cellBounds.x
                            if (relativeX >= cellBounds.width - JBUI.scale(24)) {
                                onSessionClosed(activeSessionListModel.getElementAt(index))
                            }
                        }
                    }

                    override fun mousePressed(e: MouseEvent) {
                        if (e.isPopupTrigger) showActiveSessionContextMenu(e)
                    }

                    override fun mouseReleased(e: MouseEvent) {
                        if (e.isPopupTrigger) showActiveSessionContextMenu(e)
                    }
                },
            )
        }

        val header =
            JLabel("Active Sessions").apply {
                font = font.deriveFont(Font.BOLD, JBUI.Fonts.smallFont().size2D)
                foreground = JBColor.GRAY
                border = JBUI.Borders.empty(8, 8, 4, 8)
            }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(activeSessionList, BorderLayout.CENTER)
        }
    }

    private fun showActiveSessionContextMenu(e: MouseEvent) {
        val index = activeSessionList.locationToIndex(e.point)
        if (index < 0) return
        val cellBounds = activeSessionList.getCellBounds(index, index)
        if (cellBounds == null || !cellBounds.contains(e.point)) return

        activeSessionList.selectedIndex = index
        val session = activeSessionListModel.getElementAt(index)

        val group =
            DefaultActionGroup().apply {
                add(
                    object : AnAction("Close Session", "Close this session", AllIcons.Actions.Close) {
                        override fun actionPerformed(e: AnActionEvent) {
                            onSessionClosed(session)
                        }
                    },
                )
                add(
                    object : AnAction("Delete Session", "Close and delete session history", AllIcons.Actions.GC) {
                        override fun actionPerformed(e: AnActionEvent) {
                            onSessionDeleted(session)
                        }
                    },
                )
            }
        val popup =
            JBPopupFactory.getInstance().createActionGroupPopup(
                null,
                group,
                DataManager.getInstance().getDataContext(activeSessionList),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                false,
            )
        popup.show(RelativePoint(e))
    }

    private fun showHistoryContextMenu(e: MouseEvent) {
        val index = historyList.locationToIndex(e.point)
        if (index < 0) return
        val cellBounds = historyList.getCellBounds(index, index)
        if (cellBounds == null || !cellBounds.contains(e.point)) return

        val item = historyListModel.getElementAt(index)
        if (item !is SidebarItem.HistoryEntry) return

        historyList.selectedIndex = index
        val session = item.session

        val group =
            DefaultActionGroup().apply {
                add(
                    object : AnAction("Delete Session", "Delete session history", AllIcons.Actions.GC) {
                        override fun actionPerformed(e: AnActionEvent) {
                            onHistorySessionDeleted(session)
                            historyListModel.removeElementAt(index)
                        }
                    },
                )
            }
        val popup =
            JBPopupFactory.getInstance().createActionGroupPopup(
                null,
                group,
                DataManager.getInstance().getDataContext(historyList),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                false,
            )
        popup.show(RelativePoint(e))
    }

    // ── History list (below active sessions, grouped by date) ──

    private fun createHistoryList(): JComponent {
        historyList.apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            minimumSize = Dimension(0, 0)
            cellRenderer = HistorySidebarCellRenderer()
            background = JBColor.PanelBackground
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (!SwingUtilities.isLeftMouseButton(e)) return
                        val index = historyList.locationToIndex(e.point)
                        if (index < 0) return
                        val cellBounds = historyList.getCellBounds(index, index)
                        if (cellBounds == null || !cellBounds.contains(e.point)) return

                        when (val item = historyListModel.getElementAt(index)) {
                            is SidebarItem.Header -> historyList.clearSelection()
                            is SidebarItem.HistoryEntry -> {
                                activeSessionList.clearSelection()
                                val session = item.session
                                onResumeSession(
                                    session.agentType,
                                    session.sessionId,
                                    session.displayName,
                                )
                                // Refresh history so resumed session moves to active
                                loadHistory()
                            }
                        }
                    }

                    override fun mousePressed(e: MouseEvent) {
                        if (e.isPopupTrigger) showHistoryContextMenu(e)
                    }

                    override fun mouseReleased(e: MouseEvent) {
                        if (e.isPopupTrigger) showHistoryContextMenu(e)
                    }
                },
            )
        }

        val listScroll =
            JBScrollPane(historyList).apply {
                border = JBUI.Borders.empty()
            }

        val loadingPanel = createLoadingPanel()

        historyCardPanel.apply {
            add(loadingPanel, CARD_LOADING)
            add(listScroll, CARD_LIST)
            historyCardLayout.show(this, CARD_LOADING)
        }

        return historyCardPanel
    }

    private fun createLoadingPanel(): JComponent {
        val dotsLabel =
            JLabel("Loading sessions", SwingConstants.CENTER).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.PLAIN, 12f)
            }

        // Animate dots: "Loading sessions", "Loading sessions.", "Loading sessions..", "Loading sessions..."
        val dotStates = arrayOf("", ".", "..", "...")
        var dotIndex = 0
        loadingTimer = Timer(400) {
            dotIndex = (dotIndex + 1) % dotStates.size
            dotsLabel.text = "Loading sessions${dotStates[dotIndex]}"
        }.apply {
            isRepeats = true
            start()
        }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = JBColor.PanelBackground
            add(javax.swing.Box.createVerticalGlue())
            dotsLabel.alignmentX = CENTER_ALIGNMENT
            add(dotsLabel)
            add(javax.swing.Box.createVerticalGlue())
        }
    }

    // ── History loading ──

    private companion object {
        private val LOG = Logger.getInstance(SessionSidebarPanel::class.java)
        private const val CARD_LOADING = "loading"
        private const val CARD_LIST = "list"
        private const val MIN_LOADING_MS = 500L
    }

    fun loadHistory() {
        val projectPath = project.basePath ?: return

        val isInitialLoad = historyListModel.isEmpty

        if (isInitialLoad) {
            SwingUtilities.invokeLater {
                loadingTimer?.start()
                historyCardLayout.show(historyCardPanel, CARD_LOADING)
            }
        }

        val loadStartTime = System.currentTimeMillis()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val sessionManager = SessionManager.getInstance(project)
                val enabledAgents = AgentCliSettings.getInstance().enabledAgentTypes
                val openIds = mutableSetOf<String>()
                enabledAgents.forEach { agentType ->
                    openIds.addAll(sessionManager.getOpenSessionIds(agentType))
                }

                val allHistory = enabledAgents.flatMap { readHistoryForAgent(it, projectPath) }
                val filtered =
                    allHistory
                        .filter { it.sessionId !in openIds }
                        .sortedByDescending { it.timestamp }

                val grouped = groupByDate(filtered)

                // Ensure minimum loading time for smooth transition on initial load
                if (isInitialLoad) {
                    val elapsed = System.currentTimeMillis() - loadStartTime
                    val remaining = MIN_LOADING_MS - elapsed
                    if (remaining > 0) Thread.sleep(remaining)
                }

                SwingUtilities.invokeLater {
                    loadingTimer?.stop()
                    historyListModel.clear()
                    grouped.forEach { (header, sessions) ->
                        historyListModel.addElement(SidebarItem.Header(header))
                        sessions.forEach { historyListModel.addElement(SidebarItem.HistoryEntry(it)) }
                    }
                    historyCardLayout.show(historyCardPanel, CARD_LIST)
                }
            } catch (e: Exception) {
                LOG.warn("Failed to load history", e)
                SwingUtilities.invokeLater {
                    loadingTimer?.stop()
                    historyCardLayout.show(historyCardPanel, CARD_LIST)
                }
            }
        }
    }

    private fun readHistoryForAgent(
        agentType: AgentType,
        projectPath: String,
    ): List<HistoricalSession> {
        return try {
            when (agentType) {
                AgentType.CLAUDE -> ClaudeCodeHistoryReader.readHistory(projectPath)
                AgentType.CURSOR -> CursorHistoryReader.readHistory(projectPath)
                AgentType.GEMINI -> GeminiHistoryReader.readHistory(projectPath)
                AgentType.CODEX -> CodexHistoryReader.readHistory(projectPath)
            }.map { it.copy(agentType = agentType) }
        } catch (e: Exception) {
            LOG.warn("Failed to read ${agentType.displayName} history", e)
            emptyList()
        }
    }

    private fun groupByDate(sessions: List<HistoricalSession>): List<Pair<String, List<HistoricalSession>>> {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        val startOfWeek = today.with(WeekFields.of(Locale.getDefault()).dayOfWeek(), 1)

        val groups = linkedMapOf<String, MutableList<HistoricalSession>>()

        for (session in sessions) {
            val date = session.timestamp.toLocalDate()
            val group =
                when {
                    date == today -> "Today"
                    date == yesterday -> "Yesterday"
                    !date.isBefore(startOfWeek) -> "This Week"
                    else -> "Older"
                }
            groups.getOrPut(group) { mutableListOf() }.add(session)
        }

        return groups.map { (k, v) -> k to v.toList() }
    }

    // ── Active session management (public API) ──

    fun addSession(session: AgentCliSession) {
        activeSessionListModel.addElement(session)
        activeSessionList.selectedIndex = activeSessionListModel.size() - 1
        selectedSession = session
        historyList.clearSelection()
        // Refresh history to exclude the newly opened session
        loadHistory()
    }

    fun removeSession(session: AgentCliSession) {
        activeSessionListModel.removeElement(session)
        if (activeSessionListModel.size() > 0) {
            activeSessionList.selectedIndex = activeSessionListModel.size() - 1
        }
        // Refresh history so closed session reappears
        loadHistory()
    }

    fun selectSession(session: AgentCliSession) {
        val index = activeSessionListModel.indexOf(session)
        if (index >= 0) {
            activeSessionList.selectedIndex = index
            selectedSession = session
            historyList.clearSelection()
        }
    }

    // ── Cell renderers ──

    private class ActiveSessionCellRenderer : ListCellRenderer<AgentCliSession> {
        private val closeIconSize = JBUI.scale(16)

        override fun getListCellRendererComponent(
            list: JList<out AgentCliSession>,
            value: AgentCliSession,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val bg = if (isSelected) list.selectionBackground else list.background
            val fg = if (isSelected) list.selectionForeground else list.foreground

            return JPanel(BorderLayout()).apply {
                background = bg
                border = JBUI.Borders.empty(4, 8, 4, 4)
                minimumSize = Dimension(0, 0)

                val iconLabel =
                    JLabel(AllIcons.Actions.Execute).apply {
                        border = JBUI.Borders.emptyRight(4)
                    }

                val nameLabel =
                    JLabel(value.displayName).apply {
                        foreground = fg
                        font = list.font
                    }

                val agentLabel =
                    JLabel(value.agentType.displayName).apply {
                        foreground = JBColor.GRAY
                        font = JBUI.Fonts.smallFont()
                    }

                val textPanel =
                    JPanel(BorderLayout()).apply {
                        isOpaque = false
                        minimumSize = Dimension(0, 0)
                        add(nameLabel, BorderLayout.CENTER)
                        add(agentLabel, BorderLayout.SOUTH)
                    }

                val leftPanel =
                    JPanel(BorderLayout()).apply {
                        isOpaque = false
                        minimumSize = Dimension(0, 0)
                        add(iconLabel, BorderLayout.WEST)
                        add(textPanel, BorderLayout.CENTER)
                    }

                val closeLabel =
                    JLabel(AllIcons.Actions.Close).apply {
                        preferredSize = Dimension(closeIconSize, closeIconSize)
                        toolTipText = "Close session"
                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    }

                add(leftPanel, BorderLayout.CENTER)
                add(closeLabel, BorderLayout.EAST)
            }
        }
    }

    private class HistorySidebarCellRenderer : ListCellRenderer<SidebarItem> {
        override fun getListCellRendererComponent(
            list: JList<out SidebarItem>,
            value: SidebarItem,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            return when (value) {
                is SidebarItem.Header ->
                    JPanel(BorderLayout()).apply {
                        background = list.background
                        border = JBUI.Borders.empty(8, 8, 4, 8)
                        add(
                            JLabel(value.title).apply {
                                font = font.deriveFont(Font.BOLD, JBUI.Fonts.smallFont().size2D)
                                foreground = JBColor.GRAY
                            },
                            BorderLayout.WEST,
                        )
                    }

                is SidebarItem.HistoryEntry -> {
                    val session = value.session
                    val bg = if (isSelected) list.selectionBackground else list.background
                    val fg = if (isSelected) list.selectionForeground else list.foreground

                    JPanel(BorderLayout()).apply {
                        background = bg
                        border = JBUI.Borders.empty(4, 8, 4, 8)
                        minimumSize = Dimension(0, 0)

                        val titleLabel =
                            JLabel(session.displayName).apply {
                                foreground = fg
                                font = list.font
                                minimumSize = Dimension(0, preferredSize.height)
                            }

                        val metaParts = mutableListOf(session.agentType.displayName)
                        val date = session.timestamp.toLocalDate()
                        val today = LocalDate.now()
                        if (date != today && date != today.minusDays(1)) {
                            metaParts.add(session.formattedTime)
                        }
                        val metaLabel =
                            JLabel(metaParts.joinToString("  ·  ")).apply {
                                foreground = JBColor.GRAY
                                font = JBUI.Fonts.smallFont()
                            }

                        add(titleLabel, BorderLayout.CENTER)
                        add(metaLabel, BorderLayout.SOUTH)
                    }
                }
            }
        }
    }
}
