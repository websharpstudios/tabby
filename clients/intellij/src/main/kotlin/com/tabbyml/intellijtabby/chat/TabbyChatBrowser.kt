package com.tabbyml.intellijtabby.chat

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.handler.CefLoadHandlerAdapter
import com.intellij.ui.jcef.JBCefJSQuery
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceOrNull
import java.util.UUID
import com.intellij.util.ui.UIUtil
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.tabbyml.intellijtabby.settings.SettingsService
import java.awt.Color
import com.tabbyml.intellijtabby.lsp.LanguageClient
import com.tabbyml.intellijtabby.events.CombinedState
import com.tabbyml.intellijtabby.lsp.protocol.Status
import java.util.Base64
import com.intellij.openapi.editor.colors.EditorColorsManager

class TabbyBrowser(private val project: Project) {
	private var isChatPageDisplayed = false
	private val browser: JBCefBrowser
	private val messageBusConnection = project.messageBus.connect()
	private val settings = service<SettingsService>()
	private val combinedState = project.serviceOrNull<CombinedState>()

	data class DisplayChatPageOptions(val force: Boolean = false)

	init {
		val self = this

		browser = JBCefBrowser.createBuilder()
			.setOffScreenRendering(true) // On Mac, setting false will leave a white flash when opening the window
			.build()

		messageBusConnection.subscribe(LanguageClient.AgentListener.TOPIC, object : LanguageClient.AgentListener {
			override fun agentStatusChanged(status: String) {
				if (status == Status.DISCONNECTED) {
					self.displayDisconnectedPage()
				} else {
					self.displayChatPage(self.settings.serverEndpoint)
					self.refreshChatPage();
				}
			}
		})

		messageBusConnection.subscribe(SettingsService.Listener.TOPIC, object : SettingsService.Listener {
			override fun settingsChanged(settings: SettingsService.Settings) {
				self.refreshChatPage();
			}
		})

		// Listen to the message sent from the web page
		val jsQuery = JBCefJSQuery.create(browser)
		jsQuery.addHandler { message: String ->
			val jsonElement = JsonParser.parseString(message)
			when {
				jsonElement.isJsonObject -> {
					val json = jsonElement.asJsonObject
					val action = json.get("action")?.asString
					if (action == "rendered") {
						this.refreshChatPage()
						return@addHandler JBCefJSQuery.Response("")
					}
				}

				// FIXME: Refactor thread-receiving implementation
				jsonElement.isJsonArray -> {
					val jsonArray = jsonElement.asJsonArray // [commandNumber, [id, functionName, args]]
					if (jsonArray.size() >= 2) {
						try {
							val command = jsonArray[0].asInt
							if (command == 0 && jsonArray[1].isJsonArray) {
								val commandArray = jsonArray[1].asJsonArray // [id, functionName, args]
								if (commandArray.size() >= 3) {
									val functionName = commandArray[1].asString
									when (functionName) {
										"refresh" -> {
											this.displayChatPage("http://localhost:8080", DisplayChatPageOptions(force = true))
										}
									}
								}
							}
						} catch (e: Exception) {
							return@addHandler JBCefJSQuery.Response("Error: ${e.message}")
						}
					}
				}
			}

			JBCefJSQuery.Response("")
		}

		// Inject window.onReceiveMessage into browser's JS context after HTML load
		// Enables web page to send messages to IntelliJ plugin - window.onReceiveMessage(message)
		browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
			override fun onLoadingStateChange(
				browser: CefBrowser?,
				isLoading: Boolean,
				canGoBack: Boolean,
				canGoForward: Boolean
			) {
				if (!isLoading) {
					val script = """window.onReceiveMessage = function(message) {
						${jsQuery.inject("message")}
					}""".trimIndent()
					browser?.executeJavaScript(
						script,
						browser.url,
						0
					)
				}
			}
		}, browser.cefBrowser)

		// FIXME: Implement web server health detection to display the disconnected page if the server is down.
		// Note: Currently, this.combinedState.state.agentStatus is always NOT_INITIALIZED at this point.
		self.displayChatPage(self.settings.serverEndpoint)

		Disposer.register(project, browser)
	}

	// FIXME
	// listen to edit theme change and send sync-theme message to the HTML

	fun refreshChatPage () {
		if (this.combinedState?.state?.agentStatus == Status.UNAUTHORIZED || this.combinedState?.state?.agentStatus == Status.NOT_INITIALIZED) {
			return sendMessageToServer("showError", listOf(mapOf("content" to "Before you can start chatting, please take a moment to set up your credentials to connect to the Tabby server.")))
		}

		// FIXME
		// Check for chat panel availability
		// If the panel is not available, display an error message to the user

		// FIXME: Refactor thread-sending implementation
		sendMessageToServer("cleanError")
		sendMessageToServer("init", listOf(mapOf("fetcherOptions" to mapOf("authorization" to this.settings.serverToken))))
	}

	fun displayChatPage(chatEndpoint: String, opts: DisplayChatPageOptions? = null) {
		val endpoint = if (chatEndpoint.isBlank()) "http://localhost:8080" else chatEndpoint
		val cssContent = this::class.java.getResource("/chat/chat-panel.css")?.readText() ?: ""

		val theme = if (UIUtil.isUnderDarcula()) "dark" else "light"
		val editorColorsScheme: EditorColorsScheme = EditorColorsManager.getInstance().globalScheme
		val fontSize = editorColorsScheme.editorFontSize
		val backgroundColor = colorToHex(editorColorsScheme.defaultBackground)
		val foregroundColor = colorToHex(editorColorsScheme.defaultForeground)
		val borderColor = if (theme == "dark") "444444" else "B9B9B9"

		if (this.isChatPageDisplayed && opts?.force != true) return

		this.isChatPageDisplayed = true
		val htmlContent = """
            <!DOCTYPE html>
            <html  lang="en">
            <head>
                <meta charset="UTF-8" />
            	<meta name="viewport" content="width=device-width, initial-scale=1.0" />
				<style>
					$cssContent
				</style>
				<script defer>
					const syncTheme = (data = {}) => {
						const chatIframe = document.getElementById("chat");
						if (!chatIframe) return
						
						const backgroundColor = data.backgroundColor || "#${backgroundColor}"
						const foregroundColor = data.foregroundColor || "#${foregroundColor}"
						const fontSize = data.fontSize || "${fontSize}px"
						const borderColor = data.borderColor || "#${borderColor}"
						
						const varBackgroundColor = "--intellij-editor-background:" + backgroundColor
						const varForegroundColor = "--intellij-editor-foreground:" + foregroundColor
						const varFontSize = "--intellij-font-size:" + fontSize
						const varBorderColor = "--intellij-editor-border:" + borderColor
	
						const style = [varBackgroundColor, varForegroundColor, varFontSize, varBorderColor].join(";")
						chatIframe.contentWindow.postMessage({ style }, "${endpoint}");
						
						const theme = data.theme || "${theme}"
						const themeClass = "intellij " + theme
						console.log('themeClass', themeClass)
						chatIframe.contentWindow.postMessage({ themeClass }, "${endpoint}");
				 	}
			  
				 	window.onload = function () {
						const chatIframe = document.getElementById("chat");
						if (chatIframe) {
						  const clientQuery = "&client=intellij"
						  const themeQuery = "&theme=${theme}"
						  const fontSizeQuery = "&font-size=${fontSize}px"
						  const foregroundQuery = "&foreground=${foregroundColor}"
						  const backgroundQuery = "&background=${backgroundColor}"
			  
						  chatIframe.addEventListener('load', function() {
							setTimeout(() => {
								syncTheme()
								setTimeout(() => {
									window.onReceiveMessage(JSON.stringify({ action: 'rendered' }));
								}, 800)
							}, 300)
						  });
	
					 	chatIframe.src=encodeURI("${endpoint}/chat?" + clientQuery + themeQuery + fontSizeQuery + foregroundQuery + backgroundQuery)
					}
					
					window.addEventListener("message", (event) => {
						  if (!chatIframe) return
						  if (event.data) {
							if (event.data === "quilt.threads.pong") return // @quilted/threads message
							if (event.data.fromClient) {
							 	if (event.data.action === 'sync-theme') {
								  syncTheme(event.data.data);
								  return;
								}
								chatIframe.contentWindow.postMessage(event.data.fromClient, "${endpoint}");
							} else {
								window.onReceiveMessage(JSON.stringify(event.data));
							}
						  }
					});
				  }
				</script>
            </head>
            <body >
                <iframe
					id="chat"
              		allow="clipboard-read; clipboard-write" />
            </body>
            </html>
        """.trimIndent()

		this.browser.loadHTML(htmlContent)
	}

	fun displayDisconnectedPage() {
		this.isChatPageDisplayed = false

		val cssContent = this::class.java.getResource("/chat/chat-panel.css")?.readText() ?: ""
		val logoContent = this::class.java.getResource("/META-INF/pluginIcon.svg")?.readText() ?: ""
		val encodedLogo = Base64.getEncoder().encodeToString(logoContent.toByteArray())
		val logoDataUrl = "data:image/svg+xml;base64,$encodedLogo"

		val editorColorsScheme: EditorColorsScheme = EditorColorsManager.getInstance().globalScheme
		val foregroundColor = colorToHex(editorColorsScheme.defaultForeground)
		val htmlContent = """
            <!DOCTYPE html>
            <html lang="en">
			  <head>
				<meta charset="UTF-8" />
				<meta name="viewport" content="width=device-width, initial-scale=1.0" />
				<style>
					$cssContent
				</style>
				<style>
					* {
						color: #${foregroundColor};
					}
				</style>
			  </head>
			  <body>
				<main class='static-content'>
				  <div class='avatar'>
					<img src="$logoDataUrl" alt="Tabby Logo" />
					<p>Tabby</p>
				  </div>
				  <h4 class='title'>Welcome to Tabby Chat!</h4>
				  <p>To start chatting, please set up your Tabby server. Ensure that your Tabby server is properly configured and connected.</p>
				</main>
			  </body>
			</html>
        """.trimIndent()
		this.browser.loadHTML(htmlContent)
	}

	fun sendMessageToChat(message: Any) {
		val gson = Gson()
		val jsonString = gson.toJson(message)
		val jsCode = """
			(function() {
				var message = JSON.parse('$jsonString');
				window.postMessage({ fromClient: message }, '*');
			})();
		""".trimIndent()
		browser.cefBrowser.executeJavaScript(jsCode, null, 0)
	}

	fun getBrowserComponent() = browser.component

	fun colorToHex(color: Color): String {
		return String.format("%02x%02x%02x", color.red, color.green, color.blue)
	}

	// FIXME: Refactor thread-sending implementation
	// @reference: https://github.com/lemonmade/quilt/blob/main/packages/threads/source/targets/target.ts#L89
	fun sendMessageToServer(methodName: String,	params: List<Any?>? = null) {
		val uuid = UUID.randomUUID()
		val threadMessage = listOf(
			0, // 0 means CALL in @thread protocal
			listOf(
				uuid.toString(),
				methodName,
				params ?: emptyList<Any?>()
			)
		)
		sendMessageToChat(threadMessage)
	}
}