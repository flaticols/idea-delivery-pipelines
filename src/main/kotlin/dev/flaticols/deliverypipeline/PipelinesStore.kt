package dev.flaticols.deliverypipeline

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.flaticols.deliverypipeline.model.PipelineRef

/**
 * The watched pipelines, persisted per IDE project (`.idea/deliveryPipelines.xml`)
 * so a team can share the list through VCS.
 */
@Service(Service.Level.PROJECT)
@State(name = "DeliveryPipelines", storages = [Storage("deliveryPipelines.xml")])
class PipelinesStore : PersistentStateComponent<PipelinesStore.State> {

    class Entry {
        var project: String = ""
        var region: String = ""
        var pipeline: String = ""
    }

    class State {
        var entries: MutableList<Entry> = mutableListOf()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun refs(): List<PipelineRef> =
        state.entries.map { PipelineRef(it.project, it.region, it.pipeline) }

    fun add(ref: PipelineRef) {
        if (ref in refs()) return
        state.entries += Entry().apply {
            project = ref.gcpProject
            region = ref.region
            pipeline = ref.pipeline
        }
    }

    fun remove(ref: PipelineRef) {
        state.entries.removeAll {
            it.project == ref.gcpProject && it.region == ref.region && it.pipeline == ref.pipeline
        }
    }

    companion object {
        fun getInstance(project: Project): PipelinesStore = project.service()
    }
}
