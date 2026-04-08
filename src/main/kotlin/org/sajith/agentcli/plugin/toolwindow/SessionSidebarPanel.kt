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
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import org.sajith.agentcli.plugin.AgentType
import org.sajith.agentcli.plugin.session.AgentCliSession
import org.sajith.agentcli.plugin.session.ClaudeCodeHistoryReader
import org.sajith.agentcli.plugin.session.CursorHistoryReader
import org.sajith.agentcli.plugin.session.GeminiHistoryReader
import org.sajith.agentcli.plugin.session.SessionManager
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.border.MatteBorder

class SessionSidebarPanel(
    private val project: Project,
    private val onNewSession: (agentType: AgentType) -> Unit,
    private val onSessionSelected: (AgentCliSession) -> Unit,
    private val onSessionClosed: (AgentCliSession) -> Unit,
    private val onResumeSession: (agentType: AgentType, sessionId: String, title: String?) -> Unit
) : JPanel(BorderLayout()) {

    private val sessionListModel = DefaultListModel<AgentCliSession>()
    private val sessionList = JBList(sessionListModel)
    private var selectedSession: AgentCliSession? = null

    private val sessionListPanel: JPanel
    private var isCollapsed = false

    init {
        background = JBColor.PanelBackground

        add(createIconStrip(), BorderLayout.WEST)

        sessionListPanel = JPanel(BorderLayout()).apply {
            background = JBColor.PanelBackground
            border = MatteBorder(0, 0, 0, 1, JBColor.border())
            preferredSize = Dimension(JBUI.scale(160), 0)
            minimumSize = Dimension(JBUI.scale(120), 0)
            add(createSessionList(), BorderLayout.CENTER)
        }
        add(sessionListPanel, BorderLayout.CENTER)
    }

    private fun createIconStrip(): JComponent {
        val group = DefaultActionGroup().apply {
            add(object : AnAction("New Session", "Create a new session", AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) {
                    showAgentTypePopup(e) { agentType -> onNewSession(agentType) }
                }
            })
            add(object : AnAction("Session History", "Browse session history", AllIcons.Vcs.History) {
                override fun actionPerformed(e: AnActionEvent) {
                    showAgentTypePopup(e) { agentType -> showHistoryDialog(agentType) }
                }
            })
            add(object : AnAction(
                "Toggle Sessions Panel",
                "Show or hide the sessions panel",
                AllIcons.Actions.ArrowCollapse
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    isCollapsed = !isCollapsed
                    sessionListPanel.isVisible = !isCollapsed
                    e.presentation.icon =
                        if (isCollapsed) AllIcons.Actions.ArrowExpand else AllIcons.Actions.ArrowCollapse
                    revalidate()
                    repaint()
                }
            })
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, false)
        toolbar.targetComponent = this
        toolbar.component.border = MatteBorder(0, 0, 0, 1, JBColor.border())
        return toolbar.component
    }

    private fun showAgentTypePopup(e: AnActionEvent, onSelected: (AgentType) -> Unit) {
        val group = DefaultActionGroup().apply {
            AgentType.entries.forEach { agentType ->
                add(object : AnAction(agentType.displayName) {
                    override fun actionPerformed(e: AnActionEvent) {
                        onSelected(agentType)
                    }
                })
            }
        }
        val component = e.inputEvent?.component ?: this
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            null, group, DataManager.getInstance().getDataContext(component),
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false
        )
        popup.showUnderneathOf(component)
    }

    private fun createSessionList(): JComponent {
        sessionList.apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = SessionListCellRenderer()
            background = JBColor.PanelBackground
            addListSelectionListener { e ->
                if (!e.valueIsAdjusting) {
                    sessionList.selectedValue?.let { session ->
                        selectedSession = session
                        onSessionSelected(session)
                    }
                }
            }
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    handleSessionListMouseClicked(e)
                }
                override fun mousePressed(e: MouseEvent) {
                    if (e.isPopupTrigger) showSessionContextMenu(e)
                }
                override fun mouseReleased(e: MouseEvent) {
                    if (e.isPopupTrigger) showSessionContextMenu(e)
                }
            })
        }

        return JBScrollPane(sessionList).apply {
            border = JBUI.Borders.empty()
        }
    }

    private fun showSessionContextMenu(e: MouseEvent) {
        val index = sessionList.locationToIndex(e.point)
        if (index < 0) return
        val cellBounds = sessionList.getCellBounds(index, index)
        if (cellBounds == null || !cellBounds.contains(e.point)) return

        sessionList.selectedIndex = index
        val session = sessionListModel.getElementAt(index)

        val group = DefaultActionGroup().apply {
            add(object : AnAction("Close Session", "Close this session", AllIcons.Actions.Close) {
                override fun actionPerformed(e: AnActionEvent) {
                    onSessionClosed(session)
                }
            })
        }
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            null, group, DataManager.getInstance().getDataContext(sessionList),
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false
        )
        popup.show(RelativePoint(e))
    }

    private fun handleSessionListMouseClicked(e: MouseEvent) {
        if (SwingUtilities.isMiddleMouseButton(e)) {
            val index = sessionList.locationToIndex(e.point)
            if (index >= 0) {
                val cellBounds = sessionList.getCellBounds(index, index)
                if (cellBounds != null && cellBounds.contains(e.point)) {
                    onSessionClosed(sessionListModel.getElementAt(index))
                }
            }
        }
    }

    private fun showHistoryDialog(agentType: AgentType) {
        val projectPath = project.basePath ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            val openIds = SessionManager.getInstance(project).getOpenSessionIds(agentType)
            val history = when (agentType) {
                AgentType.CLAUDE -> ClaudeCodeHistoryReader.readHistory(projectPath)
                AgentType.CURSOR -> CursorHistoryReader.readHistory(projectPath)
                AgentType.GEMINI -> GeminiHistoryReader.readHistory(projectPath)
            }.filter { it.sessionId !in openIds }

            SwingUtilities.invokeLater {
                if (history.isEmpty()) {
                    JOptionPane.showMessageDialog(
                        this,
                        "No ${agentType.displayName} session history found for this project.",
                        "Session History",
                        JOptionPane.INFORMATION_MESSAGE
                    )
                    return@invokeLater
                }

                SessionHistoryDialog(project, history) { sessionId, title ->
                    onResumeSession(agentType, sessionId, title)
                }.show()
            }
        }
    }

    fun addSession(session: AgentCliSession) {
        sessionListModel.addElement(session)
        sessionList.selectedIndex = sessionListModel.size() - 1
        selectedSession = session
    }

    fun removeSession(session: AgentCliSession) {
        sessionListModel.removeElement(session)
        if (sessionListModel.size() > 0) {
            sessionList.selectedIndex = sessionListModel.size() - 1
        }
    }

    fun selectSession(session: AgentCliSession) {
        val index = sessionListModel.indexOf(session)
        if (index >= 0) {
            sessionList.selectedIndex = index
            selectedSession = session
        }
    }

    companion object {
        private val LOG = Logger.getInstance(SessionSidebarPanel::class.java)
    }

    private class SessionListCellRenderer : ColoredListCellRenderer<AgentCliSession>() {
        override fun customizeCellRenderer(
            list: JList<out AgentCliSession>,
            value: AgentCliSession,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            append(value.displayName)
            append("  ${value.formattedTime}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }
}
