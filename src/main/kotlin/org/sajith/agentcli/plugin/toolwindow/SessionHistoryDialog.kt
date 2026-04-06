package org.sajith.agentcli.plugin.toolwindow

import org.sajith.agentcli.plugin.session.HistoricalSession
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class SessionHistoryDialog(
    project: Project,
    private val sessions: List<HistoricalSession>,
    private val onResume: (sessionId: String, title: String?) -> Unit
) : DialogWrapper(project, true) {

    private val listModel = DefaultListModel<HistoricalSession>()
    private val sessionList = JBList(listModel)

    var selectedSessionId: String? = null
        private set

    init {
        title = "Claude Code Session History"
        setOKButtonText("Resume")
        sessions.forEach { listModel.addElement(it) }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(JBUI.scale(500), JBUI.scale(400))

        sessionList.apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = HistoryListCellRenderer()

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount != 2) return
                    val index = sessionList.locationToIndex(e.point)
                    if (index < 0) return
                    resumeSession(listModel.getElementAt(index))
                    close(OK_EXIT_CODE)
                }
            })

            addListSelectionListener {
                isOKActionEnabled = sessionList.selectedIndex >= 0
            }
        }

        val scrollPane = JBScrollPane(sessionList).apply {
            border = JBUI.Borders.empty()
        }

        val headerLabel = JLabel("Select a session to resume:").apply {
            border = JBUI.Borders.emptyBottom(8)
            foreground = JBColor.foreground()
        }

        panel.add(headerLabel, BorderLayout.NORTH)
        panel.add(scrollPane, BorderLayout.CENTER)

        isOKActionEnabled = false
        return panel
    }

    override fun doOKAction() {
        sessionList.selectedValue?.let { resumeSession(it) }
        super.doOKAction()
    }

    private fun resumeSession(session: HistoricalSession) {
        selectedSessionId = session.sessionId
        onResume(session.sessionId, session.customTitle.ifBlank { null })
    }

    private class HistoryListCellRenderer : ListCellRenderer<HistoricalSession> {
        override fun getListCellRendererComponent(
            list: JList<out HistoricalSession>,
            value: HistoricalSession,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            return JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(8, 12)
                background = if (isSelected) list.selectionBackground else list.background

                val nameLabel = JLabel(value.displayName).apply {
                    foreground = if (isSelected) list.selectionForeground else list.foreground
                    font = font.deriveFont(Font.PLAIN, 13f)
                }

                val metaLabel = JLabel("${value.formattedTime}  \u00B7  ${value.messageCount} messages").apply {
                    foreground = if (isSelected) {
                        list.selectionForeground.let { Color(it.red, it.green, it.blue, 180) }
                    } else {
                        JBColor.GRAY
                    }
                    font = font.deriveFont(Font.PLAIN, 11f)
                    border = JBUI.Borders.emptyTop(2)
                }

                add(nameLabel, BorderLayout.CENTER)
                add(metaLabel, BorderLayout.SOUTH)
            }
        }
    }
}
