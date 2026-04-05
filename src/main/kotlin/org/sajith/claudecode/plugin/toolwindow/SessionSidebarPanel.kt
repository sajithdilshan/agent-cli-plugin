package org.sajith.claudecode.plugin.toolwindow

import org.sajith.claudecode.plugin.session.ClaudeCodeHistoryReader
import org.sajith.claudecode.plugin.session.ClaudeCodeSession
import org.sajith.claudecode.plugin.session.SessionManager
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
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
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
    private val onNewSession: () -> Unit,
    private val onSessionSelected: (ClaudeCodeSession) -> Unit,
    private val onSessionClosed: (ClaudeCodeSession) -> Unit,
    private val onResumeSession: (sessionId: String, title: String?) -> Unit
) : JPanel(BorderLayout()) {

    private val sessionListModel = DefaultListModel<ClaudeCodeSession>()
    private val sessionList = JBList(sessionListModel)
    private var selectedSession: ClaudeCodeSession? = null

    init {
        preferredSize = Dimension(JBUI.scale(180), 0)
        minimumSize = Dimension(JBUI.scale(140), 0)
        background = JBColor.PanelBackground
        border = MatteBorder(0, 0, 0, 1, JBColor.border())

        add(createToolbar(), BorderLayout.NORTH)
        add(createSessionList(), BorderLayout.CENTER)
    }

    private fun createToolbar(): JPanel {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4)))
        toolbar.background = JBColor.PanelBackground
        toolbar.border = BorderFactory.createCompoundBorder(
            MatteBorder(0, 0, 1, 0, JBColor.border()),
            JBUI.Borders.empty(2)
        )

        val newSessionButton = JButton("+").apply {
            toolTipText = "New Claude Code Session"
            preferredSize = Dimension(JBUI.scale(28), JBUI.scale(28))
            isFocusPainted = false
            addActionListener { onNewSession() }
        }

        val historyButton = JButton("\u29D6").apply {
            toolTipText = "Session History"
            preferredSize = Dimension(JBUI.scale(28), JBUI.scale(28))
            font = font.deriveFont(Font.PLAIN, 11f)
            isFocusPainted = false
            addActionListener { showHistoryDialog() }
        }

        toolbar.add(newSessionButton)
        toolbar.add(historyButton)

        return toolbar
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
            })
        }

        return JBScrollPane(sessionList).apply {
            border = JBUI.Borders.empty()
        }
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
        val closeButtonX = cellBounds.width - JBUI.scale(24)
        if (relativeX >= closeButtonX) {
            onSessionClosed(sessionListModel.getElementAt(index))
        }
    }

    private fun showHistoryDialog() {
        val projectPath = project.basePath ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            val openIds = SessionManager.getInstance(project).openClaudeSessionIds
            val history = ClaudeCodeHistoryReader.readHistory(projectPath)
                .filter { it.sessionId !in openIds }

            SwingUtilities.invokeLater {
                if (history.isEmpty()) {
                    JOptionPane.showMessageDialog(
                        this,
                        "No session history found for this project.",
                        "Session History",
                        JOptionPane.INFORMATION_MESSAGE
                    )
                    return@invokeLater
                }

                SessionHistoryDialog(project, history) { sessionId, title ->
                    onResumeSession(sessionId, title)
                }.show()
            }
        }
    }

    fun addSession(session: ClaudeCodeSession) {
        sessionListModel.addElement(session)
        sessionList.selectedIndex = sessionListModel.size() - 1
        selectedSession = session
    }

    fun removeSession(session: ClaudeCodeSession) {
        sessionListModel.removeElement(session)
        if (sessionListModel.size() > 0) {
            sessionList.selectedIndex = sessionListModel.size() - 1
        }
    }

    fun selectSession(session: ClaudeCodeSession) {
        val index = sessionListModel.indexOf(session)
        if (index >= 0) {
            sessionList.selectedIndex = index
            selectedSession = session
        }
    }

    private class SessionListCellRenderer : ListCellRenderer<ClaudeCodeSession> {
        override fun getListCellRendererComponent(
            list: JList<out ClaudeCodeSession>,
            value: ClaudeCodeSession,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            return JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(4, 8)
                background = if (isSelected) {
                    list.selectionBackground
                } else {
                    list.background
                }

                val textPanel = JPanel(BorderLayout()).apply {
                    isOpaque = false

                    val nameLabel = JLabel(value.displayName).apply {
                        foreground = if (isSelected) list.selectionForeground else list.foreground
                        font = font.deriveFont(Font.PLAIN, 12f)
                    }

                    val timeLabel = JLabel(value.formattedTime).apply {
                        foreground = JBColor.GRAY
                        font = font.deriveFont(Font.PLAIN, 10f)
                    }

                    add(nameLabel, BorderLayout.CENTER)
                    add(timeLabel, BorderLayout.SOUTH)
                }

                val closeLabel = JLabel("\u2715").apply {
                    foreground = if (isSelected) list.selectionForeground else JBColor.GRAY
                    font = font.deriveFont(Font.PLAIN, 11f)
                    preferredSize = Dimension(JBUI.scale(20), JBUI.scale(20))
                    horizontalAlignment = SwingConstants.CENTER
                    toolTipText = "Close session"
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                }

                add(textPanel, BorderLayout.CENTER)
                add(closeLabel, BorderLayout.EAST)
            }
        }
    }
}
