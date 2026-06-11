package dev.flaticols.deliverypipeline.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CheckBoxList
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.FormBuilder
import dev.flaticols.deliverypipeline.gcloud.CloudDeployApi
import dev.flaticols.deliverypipeline.model.PipelineRef
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent

/**
 * Searchable picker: GCP projects autocomplete (loaded from Cloud Resource
 * Manager on open), region presets, and a filterable multi-select pipeline
 * list. Ticked pipelines survive filtering.
 */
class AddPipelineDialog(private val ideProject: Project) : DialogWrapper(ideProject) {

    private val projectsProvider =
        TextFieldWithAutoCompletion.StringsCompletionProvider(emptyList(), null)
    private val projectField =
        TextFieldWithAutoCompletion(ideProject, projectsProvider, true, "")
    private val regionBox = ComboBox(REGIONS).apply {
        isEditable = true
        selectedItem = "us-central1"
    }
    private val searchField = SearchTextField(false)
    private val pipelineList = CheckBoxList<String>()
    private val status = JBLabel(" ")

    /** Full unfiltered listing + which project/region it came from. */
    private var allPipelines: List<String> = emptyList()
    private var listedProject = ""
    private var listedRegion = ""

    /** Ticked pipelines — preserved while the filter changes the visible list. */
    private val checked = linkedSetOf<String>()

    init {
        title = "Add GCP Delivery Pipelines"
        setOKButtonText("Add")
        init()
        loadProjects()
    }

    override fun createCenterPanel(): JComponent {
        val load = JButton("List Pipelines")
        load.addActionListener { loadPipelines(load) }

        pipelineList.setEmptyText("Pick project and region, then List Pipelines")
        pipelineList.setCheckBoxListListener { index, value ->
            pipelineList.getItemAt(index)?.let { if (value) checked.add(it) else checked.remove(it) }
        }
        searchField.textEditor.emptyText.text = "Filter pipelines"
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refilter()
        })

        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("GCP project:", projectField)
            .addLabeledComponent("Region:", regionBox)
            .addComponent(load)
            .addComponent(searchField)
            .addComponentFillVertically(JBScrollPane(pipelineList), 4)
            .addComponent(status)
            .panel
        panel.preferredSize = Dimension(480, 380)
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = projectField

    private fun loadProjects() {
        status.text = "Loading GCP projects…"
        AppExecutorUtil.getAppExecutorService().execute {
            val result = runCatching { CloudDeployApi.listProjects() }
            SwingUtilities.invokeLater {
                if (isDisposed) return@invokeLater
                result.onSuccess { ids ->
                    projectsProvider.setItems(ids)
                    status.text = "${ids.size} projects — type to autocomplete"
                }.onFailure {
                    status.text = "Project autocomplete unavailable (${it.message}) — type the project id"
                }
            }
        }
    }

    private fun loadPipelines(button: JButton) {
        val gcpProject = projectField.text.trim()
        val region = regionText()
        if (gcpProject.isEmpty() || region.isEmpty()) {
            status.text = "Pick project and region first"
            return
        }
        button.isEnabled = false
        status.text = "Loading pipelines from $gcpProject/$region…"
        AppExecutorUtil.getAppExecutorService().execute {
            val result = runCatching { CloudDeployApi.listPipelines(gcpProject, region) }
            SwingUtilities.invokeLater {
                if (isDisposed) return@invokeLater
                button.isEnabled = true
                result.onSuccess { names ->
                    allPipelines = names
                    listedProject = gcpProject
                    listedRegion = region
                    checked.clear() // selections belong to the previous listing
                    refilter()
                    status.text =
                        if (names.isEmpty()) "No delivery pipelines in $gcpProject/$region"
                        else "${names.size} pipelines — tick the ones to watch"
                }.onFailure {
                    status.text = it.message ?: "Cloud Deploy request failed"
                }
            }
        }
    }

    private fun refilter() {
        val query = searchField.text.trim().lowercase()
        pipelineList.clear()
        allPipelines
            .filter { query.isEmpty() || query in it.lowercase() }
            .forEach { pipelineList.addItem(it, it, it in checked) }
    }

    private fun regionText(): String =
        (regionBox.editor.item ?: regionBox.selectedItem)?.toString()?.trim().orEmpty()

    fun selectedRefs(): List<PipelineRef> =
        checked.map { PipelineRef(listedProject, listedRegion, it) }

    override fun doValidate(): ValidationInfo? {
        if (checked.isEmpty()) return ValidationInfo("Tick at least one pipeline", pipelineList)
        return null
    }

    private companion object {
        val REGIONS = arrayOf(
            "us-central1", "us-east1", "us-west1", "northamerica-northeast1",
            "europe-west1", "europe-west2", "europe-west3", "europe-north1",
            "asia-east1", "asia-northeast1", "asia-south1", "australia-southeast1",
        )
    }
}
