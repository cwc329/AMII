package io.unthrottled.amii.memes

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import io.unthrottled.amii.assets.AudibleContent
import io.unthrottled.amii.assets.VisualMemeContent
import io.unthrottled.amii.config.Config
import io.unthrottled.amii.config.getConfig
import io.unthrottled.amii.config.ui.NotificationAnchor
import io.unthrottled.amii.events.UserEvent
import io.unthrottled.amii.memes.player.MemePlayer
import io.unthrottled.amii.memes.player.MemePlayerFactory
import io.unthrottled.amii.tools.toOptional
import javax.swing.JLayeredPane

enum class MemeMetadata {
  RUN_ON_NON_UI_THREAD
}

enum class Comparison {
  GREATER, EQUAL, LESSER, UNKNOWN
}

interface MemeDisplayListener {
  companion object {
    val TOPIC = Topic.create("Meme Display Events", MemeDisplayListener::class.java)
  }

  fun onDisplay(visualMemeId: String)
}

val DEFAULT_MEME_LISTENER: MemeLifecycleListener =
  object : MemeLifecycleListener {}

interface MemeLifecycleListener {

  // user triggered event
  fun onDismiss() {}

  fun onClick(clickEvent: ClickEvent) {}

  fun onRemoval() {}

  fun onDisplay() {}
}

class MemeEvent(
  val meme: Meme,
  val userEvent: UserEvent,
  val metadata: Map<String, Any> = emptyMap(),
  private val comparator: (MemeEvent) -> Comparison = { Comparison.EQUAL },
) : Disposable {

  fun clone(): MemeEvent =
    MemeEvent(
      meme = meme.clone(),
      userEvent,
      metadata,
      comparator,
    )
  fun compareTo(other: MemeEvent): Comparison =
    comparator(other)

  override fun dispose() {
    meme.dispose()
  }
}

@Suppress("LongParameterList")
class Meme(
  private val memePlayer: MemePlayer?,
  private val memePanel: MemePanel,
  val metadata: Map<String, Any>,
  private val project: Project,
  val visualMemeContent: VisualMemeContent,
) : Disposable {

  fun clone(): Meme =
    Meme(
      memePlayer?.clone(),
      memePanel.clone(),
      metadata,
      project,
      visualMemeContent
    )
  class Builder(
    private val visualMemeContent: VisualMemeContent,
    private val audibleContent: AudibleContent?,
    private val rootPane: JLayeredPane,
    private val project: Project,
  ) {
    private var notificationMode = Config.instance.notificationMode
    private var notificationAnchor = Config.instance.notificationAnchor
    private var soundEnabled = Config.instance.soundEnabled
    private var memeDisplayInvulnerabilityDuration = Config.instance.memeDisplayInvulnerabilityDuration
    private var memeDisplayTimedDuration = Config.instance.memeDisplayTimedDuration
    private var metaData: Map<String, Any> = emptyMap()

    fun withDismissalMode(newDismissalOption: PanelDismissalOptions): Builder {
      notificationMode = newDismissalOption
      return this
    }

    fun withSound(newSoundOption: Boolean): Builder {
      soundEnabled = newSoundOption
      return this
    }

    fun withMetaData(newMetaData: Map<String, Any>): Builder {
      metaData = newMetaData
      return this
    }

    fun withAnchor(newAnchor: NotificationAnchor): Builder {
      notificationAnchor = newAnchor
      return this
    }

    fun build(): Meme {
      val memePlayer = audibleContent.toOptional()
        .filter { soundEnabled }
        .map { MemePlayerFactory.createPlayer(it) }
        .orElse(null)
      return Meme(
        memePlayer,
        MemePanel(
          rootPane,
          visualMemeContent,
          memePlayer,
          MemePanelSettings(
            notificationMode,
            notificationAnchor,
            memeDisplayInvulnerabilityDuration,
            memeDisplayTimedDuration,
          )
        ),
        metaData,
        project,
        visualMemeContent,
      )
    }
  }

  fun display() {
    if (memePlayer != null) {
      ApplicationManager.getApplication().executeOnPooledThread {
        memePlayer.play()
      }
    }

    if (metadata[MemeMetadata.RUN_ON_NON_UI_THREAD.name] == true) {
      // allows the meme to show up when a Dialog is open :)
      ApplicationManager.getApplication().executeOnPooledThread {
        displayMeme()
      }
    } else {
      ApplicationManager.getApplication().invokeLater {
        displayMeme()
      }
    }
  }

  private fun displayMeme() {
    memePanel.display(
      object : MemeLifecycleListener {
        override fun onDisplay() {
          ApplicationManager.getApplication().messageBus.syncPublisher(MemeDisplayListener.TOPIC)
            .onDisplay(memePanel.visualMeme.id)

          listeners.forEach {
            it.onDisplay()
          }
        }

        override fun onClick(clickEvent: ClickEvent) {
          if (
            ApplicationManager.getApplication().getConfig().infoOnClick &&
            clickEvent == ClickEvent.LEFT
          ) {
            project.memeInfoService().displayInfo(visualMemeContent)
          } else if (clickEvent == ClickEvent.RIGHT) {
            BrowserUtil.browse(
              "https://amii-assets.unthrottled.io/assets/view/${
              memePanel.visualMeme.id
              }"
            )
          }
        }

        override fun onDismiss() {
          listeners.forEach { it.onDismiss() }
        }

        override fun onRemoval() {
          listeners.forEach {
            it.onRemoval()
          }
          memePlayer?.stop()
        }
      }
    )
  }

  private val listeners = mutableListOf<MemeLifecycleListener>()

  fun addListener(listener: MemeLifecycleListener) {
    listeners.add(listener)
  }

  fun dismiss() {
    memePanel.dismiss()
  }

  override fun dispose() {
    memePanel.dispose()
  }
}
