package org.sajith.agentcli.plugin.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import org.sajith.agentcli.plugin.AgentType
import org.sajith.agentcli.plugin.session.ClaudeCodeHistoryReader
import org.sajith.agentcli.plugin.session.AgentCliSession
import org.sajith.agentcli.plugin.session.CursorHistoryReader
import org.sajith.agentcli.plugin.session.SessionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
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
    private lateinit var collapseButton: JButton
    private var isCollapsed = false

    init {
        background = JBColor.PanelBackground

        // Narrow vertical icon strip (always visible)
        val iconStrip = createIconStrip()
        add(iconStrip, BorderLayout.WEST)

        // Collapsible session list panel
        sessionListPanel = JPanel(BorderLayout()).apply {
            background = JBColor.PanelBackground
            border = MatteBorder(0, 0, 0, 1, JBColor.border())
            preferredSize = Dimension(JBUI.scale(160), 0)
            minimumSize = Dimension(JBUI.scale(120), 0)
            add(createSessionList(), BorderLayout.CENTER)
        }
        add(sessionListPanel, BorderLayout.CENTER)
    }

    private fun createIconStrip(): JPanel {
        val strip = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = JBColor.PanelBackground
            border = MatteBorder(0, 0, 0, 1, JBColor.border())
            preferredSize = Dimension(JBUI.scale(30), 0)
        }

        val buttonSize = Dimension(JBUI.scale(28), JBUI.scale(28))

        val newSessionButton = JButton(AllIcons.General.Add).apply {
            toolTipText = "New Session"
            maximumSize = buttonSize
            preferredSize = buttonSize
            alignmentX = CENTER_ALIGNMENT
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            font = font.deriveFont(Font.BOLD, 14f)
            addActionListener { e ->
                val button = e.source as JButton
                val group = DefaultActionGroup().apply {
                    AgentType.entries.forEach { agentType ->
                        add(object : AnAction(agentType.displayName, "New ${agentType.displayName} session", null) {
                            override fun actionPerformed(e: AnActionEvent) {
                                onNewSession(agentType)
                            }
                        })
                    }
                }
                val popup = JBPopupFactory.getInstance().createActionGroupPopup(
                    null, group, DataManager.getInstance().getDataContext(button),
                    JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false
                )
                popup.showUnderneathOf(button)
            }
        }

        val historyButton = JButton(AllIcons.Vcs.History).apply {
            toolTipText = "Session History"
            maximumSize = buttonSize
            preferredSize = buttonSize
            alignmentX = CENTER_ALIGNMENT
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            font = font.deriveFont(Font.PLAIN, 12f)
            addActionListener { e ->
                val button = e.source as JButton
                val group = DefaultActionGroup().apply {
                    AgentType.entries.forEach { agentType ->
                        add(object : AnAction(agentType.displayName, "Show ${agentType.displayName} history", null) {
                            override fun actionPerformed(e: AnActionEvent) {
                                showHistoryDialog(agentType)
                            }
                        })
                    }
                }
                val popup = JBPopupFactory.getInstance().createActionGroupPopup(
                    null, group, DataManager.getInstance().getDataContext(button),
                    JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false
                )
                popup.showUnderneathOf(button)
            }
        }

        collapseButton = JButton(AllIcons.Actions.ArrowCollapse).apply {
            toolTipText = "Toggle Sessions Panel"
            maximumSize = buttonSize
            preferredSize = buttonSize
            alignmentX = CENTER_ALIGNMENT
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            font = font.deriveFont(Font.PLAIN, 10f)
            addActionListener { toggleCollapse() }
        }

        strip.add(Box.createVerticalStrut(JBUI.scale(4)))
        strip.add(newSessionButton)
        strip.add(Box.createVerticalStrut(JBUI.scale(2)))
        strip.add(historyButton)
        strip.add(Box.createVerticalStrut(JBUI.scale(2)))
        strip.add(collapseButton)
        strip.add(Box.createVerticalGlue())

        return strip
    }

    private fun toggleCollapse() {
        isCollapsed = !isCollapsed
        sessionListPanel.isVisible = !isCollapsed
        collapseButton.icon = if (isCollapsed) AllIcons.Actions.ArrowExpand else AllIcons.Actions.ArrowCollapse
        collapseButton.toolTipText = if (isCollapsed) "Show Sessions Panel" else "Hide Sessions Panel"
        revalidate()
        repaint()
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
                onSessionClosed(sessionListModel.getElementAt(index))
            }
            return
        }
        if (!SwingUtilities.isLeftMouseButton(e)) return

        val index = sessionList.locationToIndex(e.point)
        if (index < 0) return
        val cellBounds = sessionList.getCellBounds(index, index)
        val relativeX = e.x - cellBounds.x
        val closeButtonX = cellBounds.width - JBUI.scale(28)
        if (relativeX >= closeButtonX) {
            onSessionClosed(sessionListModel.getElementAt(index))
        }
    }

    private fun showHistoryDialog(agentType: AgentType) {
        val projectPath = project.basePath ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            val openIds = SessionManager.getInstance(project).getOpenSessionIds(agentType)
            val history = when (agentType) {
                AgentType.CLAUDE -> ClaudeCodeHistoryReader.readHistory(projectPath)
                AgentType.CURSOR -> CursorHistoryReader.readHistory(projectPath)
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

    private class SessionListCellRenderer : ListCellRenderer<AgentCliSession> {
        private val closeIconSize = JBUI.scale(20)

        override fun getListCellRendererComponent(
            list: JList<out AgentCliSession>,
            value: AgentCliSession,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            return JPanel(BorderLayout(JBUI.scale(2), 0)).apply {
                border = JBUI.Borders.empty(4, 6, 4, 4)
                background = if (isSelected) list.selectionBackground else list.background

                val nameLabel = JLabel(value.displayName).apply {
                    foreground = if (isSelected) list.selectionForeground else list.foreground
                    font = font.deriveFont(Font.PLAIN, 12f)
                }

                val timeLabel = JLabel(value.formattedTime).apply {
                    foreground = JBColor.GRAY
                    font = font.deriveFont(Font.PLAIN, 10f)
                }

                val textPanel = JPanel(BorderLayout()).apply {
                    isOpaque = false
                    // Constrain width so it never pushes the close button out
                    minimumSize = Dimension(0, 0)
                    add(nameLabel, BorderLayout.CENTER)
                    add(timeLabel, BorderLayout.SOUTH)
                }

                val closeLabel = JLabel(AllIcons.Actions.Close).apply {
                    foreground = if (isSelected) list.selectionForeground else JBColor.GRAY
                    preferredSize = Dimension(closeIconSize, closeIconSize)
                    minimumSize = Dimension(closeIconSize, closeIconSize)
                    maximumSize = Dimension(closeIconSize, closeIconSize)
                    horizontalAlignment = SwingConstants.CENTER
                    verticalAlignment = SwingConstants.CENTER
                    toolTipText = "Close session"
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                }

                add(textPanel, BorderLayout.CENTER)
                add(closeLabel, BorderLayout.EAST)
            }
        }
    }
}
