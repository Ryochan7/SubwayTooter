package jp.juggler.subwaytooter.action

import android.app.Dialog
import android.os.Build
import androidx.appcompat.app.AlertDialog
import jp.juggler.subwaytooter.*
import jp.juggler.subwaytooter.api.*
import jp.juggler.subwaytooter.api.entity.*
import jp.juggler.subwaytooter.dialog.AccountPicker
import jp.juggler.subwaytooter.dialog.DlgCreateAccount
import jp.juggler.subwaytooter.dialog.DlgTextInput
import jp.juggler.subwaytooter.dialog.LoginForm
import jp.juggler.subwaytooter.table.SavedAccount
import jp.juggler.subwaytooter.table.UserRelation
import jp.juggler.subwaytooter.util.LinkHelper
import jp.juggler.subwaytooter.util.openBrowser
import jp.juggler.util.*

object Action_Account {
	
	@Suppress("unused")
	private val log = LogCategory("Action_Account")
	
	// アカウントの追加
	fun add(activity : ActMain) {
		
		LoginForm.showLoginForm(
			activity,
			null
		) { dialog, instance, action ->
			TootTaskRunner(activity).run(instance, object : TootTask {
				
				override fun background(client : TootApiClient) : TootApiResult? {
					return when(action) {
						
						LoginForm.Action.Existing ->
							client.authentication1(Pref.spClientName(activity))
						
						LoginForm.Action.Create ->
							client.createUser1(Pref.spClientName(activity))
						
						LoginForm.Action.Pseudo,
						LoginForm.Action.Token -> {
							val (ti, ri) = TootInstance.get(client)
							if(ti != null) ri?.data = ti
							ri
						}
					}
				}
				
				override fun handleResult(result : TootApiResult?) {
					
					result ?: return  // cancelled.
					
					val data = result.data
					if(result.error == null && data != null) {
						when(action) {
							LoginForm.Action.Existing -> if(data is String) {
								// ブラウザ用URLが生成された
								activity.openBrowser(data.toUri())
								dialog.dismissSafe()
								return
							}
							
							LoginForm.Action.Create -> if(data is JsonObject) {
								// インスタンスを確認できた
								createAccount(
									activity,
									instance,
									data,
									dialog
								)
								return
							}
							
							LoginForm.Action.Pseudo -> if(data is TootInstance) {
								addPseudoAccount(
									activity,
									instance,
									instanceInfo = data
								) { a ->
									activity.showToast(false, R.string.server_confirmed)
									val pos = App1.getAppState(activity).column_list.size
									activity.addColumn(pos, a, ColumnType.LOCAL)
									dialog.dismissSafe()
								}
							}
							
							LoginForm.Action.Token -> if(data is TootInstance) {
								DlgTextInput.show(
									activity,
									activity.getString(R.string.access_token_or_api_token),
									null,
									callback = object : DlgTextInput.Callback {
										
										@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
										override fun onOK(
											dialog_token : Dialog,
											text : String
										) {
											
											// dialog引数が二つあるのに注意
											activity.checkAccessToken(
												dialog,
												dialog_token,
												instance,
												text,
												null
											)
											
										}
										
										override fun onEmptyError() {
											activity.showToast(true, R.string.token_not_specified)
										}
									}
								)
							}
						}
					}
					
					val errorText = result.error ?: "(no error information)"
					if(errorText.contains("SSLHandshakeException")
						&& (Build.VERSION.RELEASE.startsWith("7.0")
							|| Build.VERSION.RELEASE.startsWith("7.1")
							&& ! Build.VERSION.RELEASE.startsWith("7.1.")
							)
					) {
						AlertDialog.Builder(activity)
							.setMessage(errorText + "\n\n" + activity.getString(R.string.ssl_bug_7_0))
							.setNeutralButton(R.string.close, null)
							.show()
					} else {
						activity.showToast(true, "$errorText ${result.requestInfo}".trim())
					}
				}
			})
		}
	}
	
	private fun createAccount(
		activity : ActMain,
		instance : Host,
		client_info : JsonObject,
		dialog_host : Dialog
	) {
		DlgCreateAccount(
			activity,
			instance
		) { dialog_create, username, email, password, agreement, reason ->
			// dialog引数が二つあるのに注意
			TootTaskRunner(activity).run(instance, object : TootTask {
				
				var ta : TootAccount? = null
				var ti : TootInstance? = null
				
				override fun background(client : TootApiClient) : TootApiResult? {
					val r1 = client.createUser2Mastodon(
						client_info,
						username,
						email,
						password,
						agreement,
						reason
					)
					val ti = r1?.jsonObject ?: return r1
					
					val misskeyVersion = TootInstance.parseMisskeyVersion(ti)
					
					val parser = TootParser(
						activity,
						linkHelper = LinkHelper.create(instance, misskeyVersion = misskeyVersion)
					)
					
					this.ti = TootInstance(parser, ti)
					
					val access_token = ti.string("access_token")
						?: return TootApiResult("can't get user access token")
					
					val r2 = client.getUserCredential(access_token, misskeyVersion = misskeyVersion)
					this.ta = parser.account(r2?.jsonObject)
					if(this.ta != null) return r2
					
					val jsonObject = jsonObject {
						put("id", EntityId.CONFIRMING.toString())
						put("username", username)
						put("acct", username)
						put("acct", username)
						put("url", "https://$instance/@$username")
					}
					
					this.ta = parser.account(jsonObject)
					r1.data = jsonObject
					r1.tokenInfo = ti
					return r1
				}
				
				override fun handleResult(result : TootApiResult?) {
					val sa : SavedAccount? = null
					if(activity.afterAccountVerify(result, ta, sa, ti, instance)) {
						dialog_host.dismissSafe()
						dialog_create.dismissSafe()
					}
				}
			})
		}.show()
	}
	
	// アカウント設定
	fun setting(activity : ActMain) {
		AccountPicker.pick(
			activity,
			bAllowPseudo = true,
			bAuto = true,
			message = activity.getString(R.string.account_picker_open_setting)
		) { ai -> ActAccountSetting.open(activity, ai, ActMain.REQUEST_CODE_ACCOUNT_SETTING) }
	}
	
	// アカウントを選んでタイムラインカラムを追加
	fun timeline(
		activity : ActMain,
		pos : Int,
		type : ColumnType,
		args : Array<out Any> = emptyArray()
	) {
		
		AccountPicker.pick(
			activity,
			bAllowPseudo = type.bAllowPseudo,
			bAllowMisskey = type.bAllowMisskey,
			bAllowMastodon = type.bAllowMastodon,
			bAuto = true,
			message = activity.getString(
				R.string.account_picker_add_timeline_of,
				type.name1(activity)
			)
		) { ai ->
			when(type) {
				
				ColumnType.PROFILE -> {
					val id = ai.loginAccount?.id
					if(id != null) activity.addColumn(pos, ai, type, id)
				}
				
				ColumnType.PROFILE_DIRECTORY ->
					activity.addColumn(pos, ai, type, ai.apiHost)
				
				else -> activity.addColumn(pos, ai, type, *args)
			}
		}
	}
	
	// 投稿画面を開く。初期テキストを指定する
	fun openPost(
		activity : ActMain,
		initial_text : String? = activity.quickTootText
	) {
		activity.post_helper.closeAcctPopup()
		
		val db_id = activity.currentPostTarget?.db_id ?: - 1L
		if(db_id != - 1L) {
			ActPost.open(activity, ActMain.REQUEST_CODE_POST, db_id, initial_text = initial_text)
		} else {
			AccountPicker.pick(
				activity,
				bAllowPseudo = false,
				bAuto = true,
				message = activity.getString(R.string.account_picker_toot)
			) { ai ->
				ActPost.open(
					activity,
					ActMain.REQUEST_CODE_POST,
					ai.db_id,
					initial_text = initial_text
				)
			}
		}
	}
	
	fun endorse(
		activity : ActMain,
		access_info : SavedAccount,
		who : TootAccount,
		bSet : Boolean
	) {
		if(access_info.isMisskey) {
			activity.showToast(false, "This feature is not provided on Misskey account.")
			return
		}
		
		
		TootTaskRunner(activity).run(access_info, object : TootTask {
			
			var relation : UserRelation? = null
			
			override fun background(client : TootApiClient) : TootApiResult? {
				val result = client.request(
					"/api/v1/accounts/${who.id}/" + when(bSet) {
						true -> "pin"
						false -> "unpin"
					},
					"".toFormRequestBody().toPost()
				)
				val jsonObject = result?.jsonObject
				if(jsonObject != null) {
					val tr = parseItem(
						::TootRelationShip,
						TootParser(client.context, access_info),
						jsonObject
					)
					if(tr != null) {
						this.relation = saveUserRelation(access_info, tr)
					}
				}
				return result
			}
			
			override fun handleResult(result : TootApiResult?) {
				result ?: return
				
				if(result.error != null) {
					activity.showToast(true, result.error)
				} else {
					activity.showToast(
						false, when(bSet) {
							true -> R.string.endorse_succeeded
							else -> R.string.remove_endorse_succeeded
						}
					)
				}
			}
		})
	}
}
