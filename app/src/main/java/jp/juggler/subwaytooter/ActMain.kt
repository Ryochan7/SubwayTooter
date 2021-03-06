package jp.juggler.subwaytooter

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.*
import android.text.InputType
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.util.JsonReader
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import jp.juggler.subwaytooter.action.*
import jp.juggler.subwaytooter.api.*
import jp.juggler.subwaytooter.api.entity.*
import jp.juggler.subwaytooter.api.entity.TootStatus.Companion.findStatusIdFromUrl
import jp.juggler.subwaytooter.api.entity.TootTag.Companion.findHashtagFromUrl
import jp.juggler.subwaytooter.dialog.AccountPicker
import jp.juggler.subwaytooter.dialog.ActionsDialog
import jp.juggler.subwaytooter.dialog.DlgQuickTootMenu
import jp.juggler.subwaytooter.dialog.DlgTextInput
import jp.juggler.subwaytooter.span.MyClickableSpan
import jp.juggler.subwaytooter.table.AcctColor
import jp.juggler.subwaytooter.table.SavedAccount
import jp.juggler.subwaytooter.util.*
import jp.juggler.subwaytooter.view.*
import jp.juggler.util.*
import kotlinx.coroutines.delay
import org.apache.commons.io.IOUtils
import org.jetbrains.anko.backgroundDrawable
import org.jetbrains.anko.imageResource
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.util.*
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ActMain : AsyncActivity(), Column.Callback, View.OnClickListener,
	ViewPager.OnPageChangeListener, DrawerLayout.DrawerListener {
	
	class PhoneEnv {
		
		internal lateinit var pager : MyViewPager
		internal lateinit var pager_adapter : ColumnPagerAdapter
	}
	
	class TabletEnv {
		
		internal lateinit var tablet_pager : RecyclerView
		internal lateinit var tablet_pager_adapter : TabletColumnPagerAdapter
		internal lateinit var tablet_layout_manager : LinearLayoutManager
		internal lateinit var tablet_snap_helper : GravitySnapHelper
	}
	
	companion object {
		
		val log = LogCategory("ActMain")
		
		// リザルト
		const val RESULT_APP_DATA_IMPORT = Activity.RESULT_FIRST_USER
		
		// リクエスト
		const val REQUEST_CODE_COLUMN_LIST = 1
		const val REQUEST_CODE_ACCOUNT_SETTING = 2
		const val REQUEST_APP_ABOUT = 3
		const val REQUEST_CODE_NICKNAME = 4
		const val REQUEST_CODE_POST = 5
		const val REQUEST_CODE_COLUMN_COLOR = 6
		const val REQUEST_CODE_APP_SETTING = 7
		const val REQUEST_CODE_TEXT = 8
		const val REQUEST_CODE_LANGUAGE_FILTER = 9
		
		const val COLUMN_WIDTH_MIN_DP = 300
		
		const val STATE_CURRENT_PAGE = "current_page"
		
		// 外部からインテントを受信した後、アカウント選択中に画面回転したらアカウント選択からやり直す
		internal var sent_intent2 : Intent? = null
		
		// アプリ設定のキャッシュ
		var boostButtonSize = 1
		var replyIconSize = 1
		var headerIconSize = 1
		var stripIconSize = 1
		var screenBottomPadding = 0
		var timeline_font : Typeface = Typeface.DEFAULT
		var timeline_font_bold : Typeface = Typeface.DEFAULT_BOLD
		
		private fun Float.clipFontSize() : Float =
			if(isNaN()) this else max(1f, this)
	}
	
	// アプリ設定のキャッシュ
	var density = 0f
	var acct_pad_lr = 0
	var timeline_font_size_sp = Float.NaN
	var acct_font_size_sp = Float.NaN
	var notification_tl_font_size_sp = Float.NaN
	var header_text_size_sp = Float.NaN
	var timeline_spacing : Float? = null
	var avatarIconSize : Int = 0
	var notificationTlIconSize : Int = 0
	
	// onResume() .. onPause() の間なら真
	private var isResumed = false
	
	// onStart() .. onStop() の間なら真
	private var isStart_ = false
	
	// onActivityResultで設定されてonResumeで消化される
	// 状態保存の必要なし
	private var posted_acct : Acct? = null // acctAscii
	private var posted_status_id : EntityId? = null
	private var posted_reply_id : EntityId? = null
	private var posted_redraft_id : EntityId? = null
	
	// 画面上のUI操作で生成されて
	// onPause,onPageDestroy 等のタイミングで閉じられる
	// 状態保存の必要なし
	internal var listItemPopup : StatusButtonsPopup? = null
	
	private var phoneEnv : PhoneEnv? = null
	private var tabletEnv : TabletEnv? = null
	
	private var nScreenColumn : Int = 0
	private var nColumnWidth : Int = 0 // dividerの幅を含む
	
	private var nAutoCwCellWidth = 0
	private var nAutoCwLines = 0
	
	private var dlgPrivacyPolicy : WeakReference<Dialog>? = null
	
	private var quickTootVisibility : TootVisibility = TootVisibility.AccountSetting
	
	//////////////////////////////////////////////////////////////////
	// 変更しない変数(lateinit)
	
	private lateinit var llQuickTootBar : LinearLayout
	private lateinit var etQuickToot : MyEditText
	private lateinit var btnQuickToot : ImageButton
	private lateinit var btnQuickTootMenu : ImageButton
	private lateinit var llEmpty : View
	private lateinit var llColumnStrip : ColumnStripLinearLayout
	private lateinit var svColumnStrip : HorizontalScrollView
	private lateinit var btnMenu : ImageButton
	private lateinit var btnToot : ImageButton
	private lateinit var vFooterDivider1 : View
	private lateinit var vFooterDivider2 : View
	
	lateinit var drawer : MyDrawerLayout
	
	lateinit var post_helper : PostHelper
	
	lateinit var pref : SharedPreferences
	lateinit var handler : Handler
	lateinit var app_state : AppState
	
	//////////////////////////////////////////////////////////////////
	// 変更しない変数
	
	val follow_complete_callback : ()->Unit = {
		showToast(false, R.string.follow_succeeded)
	}
	
	val unfollow_complete_callback : ()->Unit = {
		showToast(false, R.string.unfollow_succeeded)
	}
	val cancel_follow_request_complete_callback : ()->Unit = {
		showToast(false, R.string.follow_request_cancelled)
	}
	
	val favourite_complete_callback : ()->Unit = {
		showToast(false, R.string.favourite_succeeded)
	}
	val unfavourite_complete_callback : ()->Unit = {
		showToast(false, R.string.unfavourite_succeeded)
	}
	
	val bookmark_complete_callback : ()->Unit = {
		showToast(false, R.string.bookmark_succeeded)
	}
	val unbookmark_complete_callback : ()->Unit = {
		showToast(false, R.string.unbookmark_succeeded)
	}
	
	val boost_complete_callback : ()->Unit = {
		showToast(false, R.string.boost_succeeded)
	}
	
	val unboost_complete_callback : ()->Unit = {
		showToast(false, R.string.unboost_succeeded)
	}
	
	val reaction_complete_callback : ()->Unit = {
		showToast(false, R.string.reaction_succeeded)
	}
	
	// 相対時刻の表記を定期的に更新する
	private val proc_updateRelativeTime = object : Runnable {
		override fun run() {
			handler.removeCallbacks(this)
			if(! isStart_) return
			if(Pref.bpRelativeTimestamp(pref)) {
				for(c in app_state.column_list) {
					c.fireRelativeTime()
				}
				handler.postDelayed(this, 10000L)
			}
		}
	}
	
	private val link_click_listener : (View, MyClickableSpan) -> Unit = { viewClicked, span ->
		
		val linkInfo = span.linkInfo
		var view = viewClicked
		var column : Column? = null
		var whoRef : TootAccountRef? = null
		
		while(true) {
			val tag = view.tag
			if(tag is ItemViewHolder) {
				column = tag.column
				whoRef = tag.getAccount()
				
				break
			} else if(tag is ViewHolderItem) {
				column = tag.ivh.column
				whoRef = tag.ivh.getAccount()
				break
			} else if(tag is ColumnViewHolder) {
				column = tag.column
				whoRef = null
				break
			} else if(tag is ViewHolderHeaderBase) {
				column = tag.column
				whoRef = tag.getAccount()
				break
			} else if(tag is TabletColumnViewHolder) {
				column = tag.columnViewHolder.column
				break
			} else {
				val parent = view.parent
				if(parent is View) {
					view = parent
				} else {
					break
				}
			}
		}
		val pos = nextPosition(column)
		val access_info = column?.access_info
		
		var tag_list : ArrayList<String>? = null
		
		try {
			val cs = (viewClicked as TextView).text
			if(cs is Spannable) {
				for(s in cs.getSpans(0, cs.length, MyClickableSpan::class.java)) {
					val li = s.linkInfo
					val pair = li.url.findHashtagFromUrl()
					if(pair != null) {
						if(tag_list == null) tag_list = ArrayList()
						tag_list.add(if(li.text.startsWith('#')) li.text else "#${pair.first}")
					}
				}
			}
		} catch(ex : Throwable) {
			log.trace(ex)
		}

		openCustomTab(
			this@ActMain,
			pos,
			linkInfo.url,
			accessInfo = access_info,
			tagList = tag_list,
			whoRef = whoRef,
			linkInfo = linkInfo
		)
	}
	
	private fun showQuickTootVisibility() {
		btnQuickTootMenu.imageResource =
			when(val resId = Styler.getVisibilityIconId(false, quickTootVisibility)) {
				R.drawable.ic_question -> R.drawable.ic_description
				else -> resId
			}
	}
	
	private fun performQuickTootMenu() {
		dlgQuickTootMenu.toggle()
	}
	
	private val dlgQuickTootMenu = DlgQuickTootMenu(this, object : DlgQuickTootMenu.Callback {
		
		override var visibility : TootVisibility
			get() = quickTootVisibility
			set(value) {
				if(value != quickTootVisibility) {
					quickTootVisibility = value
					pref.edit().put(Pref.spQuickTootVisibility, value.id.toString()).apply()
					showQuickTootVisibility()
				}
			}
		
		override fun onMacro(text : String) {
			val editable = etQuickToot.text
			if(editable?.isNotEmpty() == true) {
				val start = etQuickToot.selectionStart
				val end = etQuickToot.selectionEnd
				editable.replace(start, end, text)
				etQuickToot.requestFocus()
				etQuickToot.setSelection(start + text.length)
			} else {
				etQuickToot.setText(text)
				etQuickToot.requestFocus()
				etQuickToot.setSelection(text.length)
			}
		}
	})
	
	val viewPool = RecyclerView.RecycledViewPool()
	
	//////////////////////////////////////////////////////////////////
	// 読み取り専用のプロパティ
	
	override val isActivityStart : Boolean
		get() = isStart_
	
	// スマホモードなら現在のカラムを、タブレットモードなら-1Lを返す
	// (カラム一覧画面のデフォルト選択位置に使われる)
	val currentColumn : Int
		get() = phoneTab(
			{ it.pager.currentItem },
			{ - 1 }
		)
	
	// 新しいカラムをどこに挿入するか
	// 現在のページの次の位置か、終端
	val defaultInsertPosition : Int
		get() = phoneTab(
			{ it.pager.currentItem + 1 },
			{ Integer.MAX_VALUE }
		)
	
	private val TabletEnv.visibleColumnsIndices : IntRange
		get() {
			var vs = tablet_layout_manager.findFirstVisibleItemPosition()
			var ve = tablet_layout_manager.findLastVisibleItemPosition()
			if(vs == RecyclerView.NO_POSITION || ve == RecyclerView.NO_POSITION) {
				return IntRange(- 1, - 2) // empty and less than zero
			}
			
			val child = tablet_layout_manager.findViewByPosition(vs)
			val slide_ratio =
				clipRange(0f, 1f, abs((child?.left ?: 0) / nColumnWidth.toFloat()))
			if(slide_ratio >= 0.95f) {
				++ vs
				++ ve
			}
			return IntRange(vs, min(ve, vs + nScreenColumn - 1))
		}
	
	private val TabletEnv.visibleColumns : List<Column>
		get() = visibleColumnsIndices
			.mapNotNull {
				try {
					app_state.column_list[it]
				} catch(ex : Throwable) {
					null
				}
			}
	
	// デフォルトの投稿先アカウントを探す。アカウント選択が必要な状況ならnull
	val currentPostTarget : SavedAccount?
		get() = phoneTab(
			{ env ->
				val c = env.pager_adapter.getColumn(env.pager.currentItem)
				return when {
					c == null || c.access_info.isPseudo -> null
					else -> c.access_info
				}
			},
			{ env ->
				
				val db_id = Pref.lpTabletTootDefaultAccount(App1.pref)
				if(db_id != - 1L) {
					val a = SavedAccount.loadAccount(this@ActMain, db_id)
					if(a != null && ! a.isPseudo) return a
				}
				
				val accounts = ArrayList<SavedAccount>()
				for(c in env.visibleColumns) {
					try {
						val a = c.access_info
						// 画面内に疑似アカウントがあれば常にアカウント選択が必要
						if(a.isPseudo) {
							accounts.clear()
							break
						}
						// 既出でなければ追加する
						if(null == accounts.find { it == a }) accounts.add(a)
					} catch(ex : Throwable) {
					
					}
				}
				
				return when(accounts.size) {
					// 候補が1つだけならアカウント選択は不要
					1 -> accounts.first()
					// 候補が2つ以上ならアカウント選択は必要
					else -> null
				}
			})
	
	// 簡易投稿入力のテキスト
	val quickTootText : String
		get() = etQuickToot.text.toString()
	
	//////////////////////////////////////////////////////////////////
	// アクティビティイベント
	
	override fun onCreate(savedInstanceState : Bundle?) {
		log.d("onCreate")
		super.onCreate(savedInstanceState)
		requestWindowFeature(Window.FEATURE_NO_TITLE)
		App1.setActivityTheme(this, noActionBar = true)
		
		handler = App1.getAppState(this).handler
		app_state = App1.getAppState(this)
		pref = App1.pref
		
		density = app_state.density
		acct_pad_lr = (0.5f + 4f * density).toInt()
		
		timeline_font_size_sp = Pref.fpTimelineFontSize(pref).clipFontSize()
		acct_font_size_sp = Pref.fpAcctFontSize(pref).clipFontSize()
		notification_tl_font_size_sp = Pref.fpNotificationTlFontSize(pref).clipFontSize()
		header_text_size_sp = Pref.fpHeaderTextSize(pref).clipFontSize()
		
		val fv = Pref.spTimelineSpacing(pref).toFloatOrNull()
		timeline_spacing = if(fv != null && fv.isFinite() && fv != 0f) fv else null
		
		initUI()
		
		updateColumnStrip()
		
		if(app_state.column_list.isNotEmpty()) {
			
			// 前回最後に表示していたカラムの位置にスクロールする
			val column_pos = Pref.ipLastColumnPos(pref)
			log.d("ipLastColumnPos load $column_pos")
			if(column_pos in 0 until app_state.column_list.size) {
				scrollToColumn(column_pos, false)
			}
			
			// 表示位置に合わせたイベントを発行
			phoneTab(
				{ env -> onPageSelected(env.pager.currentItem) },
				{ env -> resizeColumnWidth(env) }
			)
		}
		
		PollingWorker.queueUpdateNotification(this)
		
		if(savedInstanceState != null) {
			sent_intent2?.let { handleSentIntent(it) }
		}
		
		checkPrivacyPolicy()
	}
	
	override fun onDestroy() {
		log.d("onDestroy")
		super.onDestroy()
		post_helper.onDestroy()
		
		// このアクティビティに関連する ColumnViewHolder への参照を全カラムから除去する
		for(c in app_state.column_list) {
			c.removeColumnViewHolderByActivity(this)
		}
	}
	
	override fun onNewIntent(intent : Intent?) {
		super.onNewIntent(intent)
		log.w("onNewIntent: isResumed = isResumed")
	}
	
	override fun onConfigurationChanged(newConfig : Configuration) {
		log.d("onConfigurationChanged")
		super.onConfigurationChanged(newConfig)
		if(newConfig.screenHeightDp > 0 || newConfig.screenHeightDp > 0) {
			tabOnly { env -> resizeColumnWidth(env) }
		}
	}
	
	override fun onSaveInstanceState(outState : Bundle) {
		super.onSaveInstanceState(outState)
		
		log.d("onSaveInstanceState")
		
		phoneTab(
			{ env -> outState.putInt(STATE_CURRENT_PAGE, env.pager.currentItem) },
			{ env ->
				env.tablet_layout_manager.findLastVisibleItemPosition()
					.takeIf { it != RecyclerView.NO_POSITION }
					?.let { outState.putInt(STATE_CURRENT_PAGE, it) }
			}
		)
		
		for(column in app_state.column_list) {
			column.saveScrollPosition()
		}
		
	}
	
	override fun onRestoreInstanceState(savedInstanceState : Bundle) {
		log.d("onRestoreInstanceState")
		super.onRestoreInstanceState(savedInstanceState)
		val pos = savedInstanceState.getInt(STATE_CURRENT_PAGE)
		if(pos > 0 && pos < app_state.column_list.size) {
			phoneTab(
				{ env -> env.pager.currentItem = pos },
				{ env ->
					env.tablet_layout_manager
						.smoothScrollToPosition(env.tablet_pager, null, pos)
				}
			)
		}
	}
	
	override fun onStart() {
		val tsTotal = SystemClock.elapsedRealtime()
		super.onStart()
		
		isStart_ = true
		log.d("onStart")
		
		var ts = SystemClock.elapsedRealtime()
		var te : Long
		
		// カラーカスタマイズを読み直す
		ListDivider.color = Pref.ipListDividerColor(pref)
		TabletColumnDivider.color = Pref.ipListDividerColor(pref)
		ItemViewHolder.toot_color_unlisted = Pref.ipTootColorUnlisted(pref)
		ItemViewHolder.toot_color_follower = Pref.ipTootColorFollower(pref)
		ItemViewHolder.toot_color_direct_user = Pref.ipTootColorDirectUser(pref)
		ItemViewHolder.toot_color_direct_me = Pref.ipTootColorDirectMe(pref)
		MyClickableSpan.showLinkUnderline = Pref.bpShowLinkUnderline(pref)
		MyClickableSpan.defaultLinkColor = Pref.ipLinkColor(pref).notZero()
			?: getAttributeColor(R.attr.colorLink)
		
		CustomShare.reloadCache(this, pref)
		
		te = SystemClock.elapsedRealtime()
		if(te - ts >= 100L) log.w("onStart: ${te - ts}ms : reload color")
		ts = SystemClock.elapsedRealtime()
		
		var tz = TimeZone.getDefault()
		try {
			val tz_id = Pref.spTimeZone(pref)
			if(tz_id.isNotEmpty()) {
				tz = TimeZone.getTimeZone(tz_id)
			}
		} catch(ex : Throwable) {
			log.e(ex, "getTimeZone failed.")
		}
		TootStatus.date_format.timeZone = tz
		
		te = SystemClock.elapsedRealtime()
		if(te - ts >= 100L) log.w("onStart: ${te - ts}ms : reload timezone")
		ts = SystemClock.elapsedRealtime()
		
		// バグいアカウントデータを消す
		try {
			SavedAccount.sweepBuggieData()
		} catch(ex : Throwable) {
			log.trace(ex)
		}
		
		te = SystemClock.elapsedRealtime()
		if(te - ts >= 100L) log.w("onStart: ${te - ts}ms : sweepBuggieData")
		ts = SystemClock.elapsedRealtime()
		
		// アカウント設定から戻ってきたら、カラムを消す必要があるかもしれない
		val new_order = ArrayList<Int>()
		
		for(i in 0 until app_state.column_list.size) {
			val column = app_state.column_list[i]
			
			if(! column.access_info.isNA) {
				// 存在確認
				SavedAccount.loadAccount(this@ActMain, column.access_info.db_id)
					?: continue
			}
			new_order.add(i)
		}
		
		if(new_order.size != app_state.column_list.size) {
			setOrder(new_order)
		}
		
		te = SystemClock.elapsedRealtime()
		if(te - ts >= 100L) log.w("onStart: ${te - ts}ms : column order")
		ts = SystemClock.elapsedRealtime()
		
		// 背景画像を表示しない設定が変更された時にカラムの背景を設定しなおす
		for(column in app_state.column_list) {
			column.viewHolder?.lastAnnouncementShown = 0L
			column.fireColumnColor()
		}
		
		te = SystemClock.elapsedRealtime()
		if(te - ts >= 100L) log.w("onStart: ${te - ts}ms :fireColumnColor")
		ts = SystemClock.elapsedRealtime()
		
		// 各カラムのアカウント設定を読み直す
		reloadAccountSetting()
		
		te = SystemClock.elapsedRealtime()
		if(te - ts >= 100L) log.w("onStart: ${te - ts}ms :reloadAccountSetting")
		ts = SystemClock.elapsedRealtime()
		
		// 投稿直後ならカラムの再取得を行う
		refreshAfterPost()
		
		te = SystemClock.elapsedRealtime()
		if(te - ts >= 100L) log.w("onStart: ${te - ts}ms :refreshAfterPost")
		ts = SystemClock.elapsedRealtime()
		
		// 画面復帰時に再取得やストリーミング開始を行う
		for(column in app_state.column_list) {
			column.onStart(this)
		}
		
		te = SystemClock.elapsedRealtime()
		if(te - ts >= 100L) log.w("onStart: ${te - ts}ms :column.onStart")
		ts = SystemClock.elapsedRealtime()
		
		// カラムの表示範囲インジケータを更新
		updateColumnStripSelection(- 1, - 1f)
		
		te = SystemClock.elapsedRealtime()
		if(te - ts >= 100L) log.w("onStart: ${te - ts}ms :updateColumnStripSelection")
		ts = SystemClock.elapsedRealtime()
		
		
		for(c in app_state.column_list) {
			c.fireShowContent(reason = "ActMain onStart", reset = true)
		}
		
		te = SystemClock.elapsedRealtime()
		if(te - ts >= 100L) log.w("onStart: ${te - ts}ms :fireShowContent")
		
		// 相対時刻表示
		proc_updateRelativeTime.run()
		
		
		te = SystemClock.elapsedRealtime()
		if(te - tsTotal >= 100L) log.w("onStart: ${te - tsTotal}ms : total")
		
		app_state.enableSpeech()
	}
	
	override fun onStop() {
		
		log.d("onStop")
		
		isStart_ = false
		
		handler.removeCallbacks(proc_updateRelativeTime)
		
		post_helper.closeAcctPopup()
		
		closeListItemPopup()
		
		app_state.stream_reader.stopAll()
		
		for(column in app_state.column_list) {
			column.saveScrollPosition()
		}
		app_state.saveColumnList(bEnableSpeech = false)
		
		super.onStop()
		
	}
	
	override fun onResume() {
		log.d("onResume")
		isResumed = true
		
		super.onResume()
		/*
		super.onResume() から呼ばれる isTopOfTask() が android.os.RemoteException 例外をたまに出すが、放置することにした。
		
		java.lang.RuntimeException:
		at android.app.ActivityThread.performResumeActivity (ActivityThread.java:4430)
		at android.app.ActivityThread.handleResumeActivity (ActivityThread.java:4470)
		at android.app.servertransaction.TransactionExecutor.performLifecycleSequence (TransactionExecutor.java:183)
		at android.app.servertransaction.TransactionExecutor.cycleToPath (TransactionExecutor.java:165)
		at android.app.servertransaction.TransactionExecutor.executeLifecycleState (TransactionExecutor.java:142)
		at android.app.servertransaction.TransactionExecutor.execute (TransactionExecutor.java:70)
		at android.app.ActivityThread$H.handleMessage (ActivityThread.java:2199)
		at android.os.Handler.dispatchMessage (Handler.java:112)
		at android.os.Looper.loop (Looper.java:216)
		at android.app.ActivityThread.main (ActivityThread.java:7625)
		at java.lang.reflect.Method.invoke (Native Method)
		at com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run (RuntimeInit.java:524)
		at com.android.internal.os.ZygoteInit.main (ZygoteInit.java:987)
		Caused by: java.lang.IllegalArgumentException:
		at android.os.Parcel.createException (Parcel.java:1957)
		at android.os.Parcel.readException (Parcel.java:1921)
		at android.os.Parcel.readException (Parcel.java:1871)
		at android.app.IActivityManager$Stub$Proxy.isTopOfTask (IActivityManager.java:7912)
		at android.app.Activity.isTopOfTask (Activity.java:6724)
		at android.app.Activity.onResume (Activity.java:1425)
		at androidx.fragment.app.FragmentActivity.onResume (FragmentActivity.java:456)
		at jp.juggler.subwaytooter.ActMain.onResume (ActMain.kt:685)
		at android.app.Instrumentation.callActivityOnResume (Instrumentation.java:1456)
		at android.app.Activity.performResume (Activity.java:7614)
		at android.app.ActivityThread.performResumeActivity (ActivityThread.java:4412)
		Caused by: android.os.RemoteException:
		at com.android.server.am.ActivityManagerService.isTopOfTask (ActivityManagerService.java:16128)
		at android.app.IActivityManager$Stub.onTransact (IActivityManager.java:2376)
		at com.android.server.am.ActivityManagerService.onTransact (ActivityManagerService.java:3648)
		at com.android.server.am.HwActivityManagerService.onTransact (HwActivityManagerService.java:609)
		at android.os.Binder.execTransact (Binder.java:739)
		*/
		
		MyClickableSpan.link_callback = WeakReference(link_click_listener)
		
		if(Pref.bpDontScreenOff(pref)) {
			window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
		} else {
			window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
		}
		
		// 外部から受け取ったUriの処理
		val uri = ActCallback.last_uri.getAndSet(null)
		if(uri != null) {
			handleIntentUri(uri)
		}
		
		// 外部から受け取ったUriの処理
		val intent = ActCallback.sent_intent.getAndSet(null)
		if(intent != null) {
			handleSentIntent(intent)
		}
		
	}
	
	override fun onPause() {
		log.d("onPause")
		isResumed = false
		
		// 最後に表示していたカラムの位置
		val last_pos = phoneTab(
			{ env -> env.pager.currentItem },
			{ env -> env.visibleColumnsIndices.first })
		log.d("ipLastColumnPos save $last_pos")
		pref.edit().put(Pref.ipLastColumnPos, last_pos).apply()
		
		for(column in app_state.column_list) {
			column.saveScrollPosition()
		}
		
		app_state.saveColumnList(bEnableSpeech = false)
		
		super.onPause()
	}
	
	//////////////////////////////////////////////////////////////////
	// UIイベント
	
	override fun onPageScrollStateChanged(state : Int) {
	}
	
	override fun onPageScrolled(
		position : Int,
		positionOffset : Float,
		positionOffsetPixels : Int
	) {
		updateColumnStripSelection(position, positionOffset)
	}
	
	override fun onPageSelected(position : Int) {
		handler.post {
			if(position >= 0 && position < app_state.column_list.size) {
				val column = app_state.column_list[position]
				if(! column.bFirstInitialized) {
					column.startLoading()
				}
				scrollColumnStrip(position)
				post_helper.setInstance(
					when {
						column.access_info.isNA -> null
						else -> column.access_info
					}
				)
			}
		}
	}
	
	override fun onClick(v : View) {
		when(v.id) {
			R.id.btnMenu -> if(! drawer.isDrawerOpen(GravityCompat.START)) {
				drawer.openDrawer(GravityCompat.START)
			}
			
			R.id.btnToot -> Action_Account.openPost(this@ActMain)
			
			R.id.btnQuickToot -> performQuickPost(null)
			
			R.id.btnQuickTootMenu -> performQuickTootMenu()
		}
	}
	
	////////////////////////////////////////////////////////////////////
	
	// スマホモードとタブレットモードでコードを切り替える
	private inline fun <R> phoneTab(
		codePhone : (PhoneEnv) -> R,
		codeTablet : (TabletEnv) -> R
	) : R {
		
		val pe = phoneEnv
		if(pe != null) return codePhone(pe)
		
		val te = tabletEnv
		if(te != null) return codeTablet(te)
		
		throw RuntimeException("missing phoneEnv or tabletEnv")
	}
	
	// スマホモードならラムダを実行する。タブレットモードならnullを返す
	private inline fun <R> phoneOnly(code : (PhoneEnv) -> R) : R? {
		val pe = phoneEnv
		return if(pe != null) code(pe) else null
	}
	
	// タブレットモードならラムダを実行する。スマホモードならnullを返す
	@Suppress("unused")
	private inline fun <R> tabOnly(code : (TabletEnv) -> R) : R? {
		val te = tabletEnv
		return if(te != null) code(te) else null
	}
	
	// 新しいカラムをどこに挿入するか
	// カラムの次の位置か、現在のページの次の位置か、終端
	fun nextPosition(column : Column?) : Int {
		if(column != null) {
			val pos = app_state.column_list.indexOf(column)
			if(pos != - 1) return pos + 1
		}
		return defaultInsertPosition
	}
	
	private fun refreshAfterPost() {
		val posted_acct = this.posted_acct
		val posted_status_id = this.posted_status_id
		
		if(posted_acct != null && posted_status_id == null) {
			// 予約投稿なら予約投稿リストをリロードする
			for(column in app_state.column_list) {
				if(column.type == ColumnType.SCHEDULED_STATUS
					&& column.access_info.acct == posted_acct
				) {
					column.startLoading()
				}
			}
			
		} else if(posted_acct != null && posted_status_id != null) {
			
			val posted_redraft_id = this.posted_redraft_id
			if(posted_redraft_id != null) {
				val host = posted_acct.host
				if(host != null) {
					for(column in app_state.column_list) {
						column.onStatusRemoved(host, posted_redraft_id)
					}
				}
				this.posted_redraft_id = null
			}
			
			val refresh_after_toot = Pref.ipRefreshAfterToot(pref)
			if(refresh_after_toot != Pref.RAT_DONT_REFRESH) {
				for(column in app_state.column_list) {
					if(column.access_info.acct != posted_acct) continue
					column.startRefreshForPost(
						refresh_after_toot,
						posted_status_id,
						posted_reply_id
					)
				}
			}
		}
		this.posted_acct = null
		this.posted_status_id = null
	}
	
	private fun handleSentIntent(intent : Intent) {
		sent_intent2 = intent
		
		// Galaxy S8+ で STのSSを取った後に出るポップアップからそのまま共有でSTを選ぶと何も起きない問題への対策
		handler.post {
			AccountPicker.pick(
				this,
				bAllowPseudo = false,
				bAuto = true,
				message = getString(R.string.account_picker_toot),
				dismiss_callback = { sent_intent2 = null }
			) { ai ->
				sent_intent2 = null
				ActPost.open(this@ActMain, REQUEST_CODE_POST, ai.db_id, sent_intent = intent)
			}
		}
	}
	
	fun closeListItemPopup() {
		try {
			listItemPopup?.dismiss()
		} catch(ignored : Throwable) {
		}
		listItemPopup = null
	}
	
	private fun performQuickPost(account : SavedAccount?) {
		if(account == null) {
			val a = if(tabletEnv != null && ! Pref.bpQuickTootOmitAccountSelection(pref)) {
				// タブレットモードでオプションが無効なら
				// 簡易投稿は常にアカウント選択する
				null
			} else {
				currentPostTarget
			}
			
			if(a != null && ! a.isPseudo) {
				performQuickPost(a)
			} else {
				// アカウントを選択してやり直し
				AccountPicker.pick(
					this,
					bAllowPseudo = false,
					bAuto = true,
					message = getString(R.string.account_picker_toot)
				) { ai -> performQuickPost(ai) }
			}
			return
		}
		
		post_helper.content = etQuickToot.text.toString().trim { it <= ' ' }
		post_helper.spoiler_text = null
		
		post_helper.visibility = when(quickTootVisibility) {
			TootVisibility.AccountSetting -> account.visibility
			else -> quickTootVisibility
		}
		
		post_helper.bNSFW = false
		post_helper.in_reply_to_id = null
		post_helper.attachment_list = null
		post_helper.emojiMapCustom =
			App1.custom_emoji_lister.getMap(account)
		
		
		etQuickToot.hideKeyboard()
		
		post_helper.post(account, callback = object : PostHelper.PostCompleteCallback {
			override fun onPostComplete(
				target_account : SavedAccount,
				status : TootStatus
			) {
				etQuickToot.setText("")
				posted_acct = target_account.acct
				posted_status_id = status.id
				posted_reply_id = status.in_reply_to_id
				posted_redraft_id = null
				refreshAfterPost()
			}
			
			override fun onScheduledPostComplete(target_account : SavedAccount) {
			}
		})
	}
	
	private fun isOrderChanged(new_order : ArrayList<Int>) : Boolean {
		if(new_order.size != app_state.column_list.size) return true
		var i = 0
		val ie = new_order.size
		while(i < ie) {
			if(new_order[i] != i) return true
			++ i
		}
		return false
	}
	
	override fun onActivityResult(requestCode : Int, resultCode : Int, data : Intent?) {
		log.d("onActivityResult req=$requestCode res=$resultCode data=$data")
		
		if(resultCode == Activity.RESULT_OK) {
			when(requestCode) {
				REQUEST_CODE_COLUMN_LIST -> if(data != null) {
					val order = data.getIntegerArrayListExtra(ActColumnList.EXTRA_ORDER)
					if(order != null && isOrderChanged(order)) {
						setOrder(order)
					}
					
					if(app_state.column_list.isNotEmpty()) {
						val select = data.getIntExtra(ActColumnList.EXTRA_SELECTION, - 1)
						if(0 <= select && select < app_state.column_list.size) {
							scrollToColumn(select)
						}
					}
				}
				
				REQUEST_APP_ABOUT -> if(data != null) {
					val search = data.getStringExtra(ActAbout.EXTRA_SEARCH)
					if(search?.isNotEmpty() == true) {
						Action_Account.timeline(
							this@ActMain,
							defaultInsertPosition,
							ColumnType.SEARCH,
							args = arrayOf(search, true)
						)
					}
					return
				}
				
				REQUEST_CODE_NICKNAME -> {
					
					updateColumnStrip()
					
					for(column in app_state.column_list) {
						column.fireShowColumnHeader()
					}
					
				}
				
				REQUEST_CODE_POST -> if(data != null) {
					etQuickToot.setText("")
					posted_acct =
						data.getStringExtra(ActPost.EXTRA_POSTED_ACCT)?.let { Acct.parse(it) }
					if(data.extras?.containsKey(ActPost.EXTRA_POSTED_STATUS_ID) == true) {
						posted_status_id = EntityId.from(data, ActPost.EXTRA_POSTED_STATUS_ID)
						posted_reply_id = EntityId.from(data, ActPost.EXTRA_POSTED_REPLY_ID)
						posted_redraft_id = EntityId.from(data, ActPost.EXTRA_POSTED_REDRAFT_ID)
					} else {
						posted_status_id = null
					}
				}
				
				REQUEST_CODE_COLUMN_COLOR -> if(data != null) {
					app_state.saveColumnList()
					val idx = data.getIntExtra(ActColumnCustomize.EXTRA_COLUMN_INDEX, 0)
					if(idx in app_state.column_list.indices) {
						app_state.column_list[idx].fireColumnColor()
						app_state.column_list[idx].fireShowContent(
							reason = "ActMain column color changed",
							reset = true
						)
					}
					updateColumnStrip()
				}
				
				REQUEST_CODE_LANGUAGE_FILTER -> if(data != null) {
					app_state.saveColumnList()
					val idx = data.getIntExtra(ActLanguageFilter.EXTRA_COLUMN_INDEX, 0)
					if(idx in app_state.column_list.indices) {
						app_state.column_list[idx].onLanguageFilterChanged()
					}
				}
			}
		}
		
		when(requestCode) {
			
			REQUEST_CODE_ACCOUNT_SETTING -> {
				updateColumnStrip()
				
				for(column in app_state.column_list) {
					column.fireShowColumnHeader()
				}
				
				if(resultCode == Activity.RESULT_OK && data != null) {
					openBrowser(data.data)
				} else if(resultCode == ActAccountSetting.RESULT_INPUT_ACCESS_TOKEN && data != null) {
					val db_id = data.getLongExtra(ActAccountSetting.EXTRA_DB_ID, - 1L)
					checkAccessToken2(db_id)
				}
			}
			
			REQUEST_CODE_APP_SETTING -> {
				Column.reloadDefaultColor(this, pref)
				showFooterColor()
				updateColumnStrip()
				
				if(resultCode == RESULT_APP_DATA_IMPORT) {
					importAppData(data?.data)
				}
			}
			
			REQUEST_CODE_TEXT -> when(resultCode) {
				ActText.RESULT_SEARCH_MSP -> {
					val text = data?.getStringExtra(Intent.EXTRA_TEXT) ?: ""
					addColumn(
						false,
						defaultInsertPosition,
						SavedAccount.na,
						ColumnType.SEARCH_MSP,
						text
					)
				}
				
				ActText.RESULT_SEARCH_TS -> {
					val text = data?.getStringExtra(Intent.EXTRA_TEXT) ?: ""
					addColumn(
						false,
						defaultInsertPosition,
						SavedAccount.na,
						ColumnType.SEARCH_TS,
						text
					)
				}

				ActText.RESULT_SEARCH_NOTESTOCK -> {
					val text = data?.getStringExtra(Intent.EXTRA_TEXT) ?: ""
					addColumn(
						false,
						defaultInsertPosition,
						SavedAccount.na,
						ColumnType.SEARCH_NOTESTOCK,
						text
					)
				}
			}
		}
		
		super.onActivityResult(requestCode, resultCode, data)
	}
	
	override fun onBackPressed() {
		
		// メニューが開いていたら閉じる
		if(drawer.isDrawerOpen(GravityCompat.START)) {
			drawer.closeDrawer(GravityCompat.START)
			return
		}
		
		// カラムが0個ならアプリを終了する
		if(app_state.column_list.isEmpty()) {
			this@ActMain.finish()
			return
		}
		
		// カラム設定が開いているならカラム設定を閉じる
		if(closeColumnSetting()) {
			return
		}
		
		fun getClosableColumnList() : List<Column> {
			val visibleColumnList = ArrayList<Column>()
			phoneTab({ env ->
				try {
					visibleColumnList.add(app_state.column_list[env.pager.currentItem])
				} catch(ex : Throwable) {
				}
			}, { env ->
				visibleColumnList.addAll(env.visibleColumns)
			})
			
			return visibleColumnList.filter { ! it.dont_close }
			
		}
		
		// カラムが1個以上ある場合は設定に合わせて挙動を変える
		when(Pref.ipBackButtonAction(pref)) {
			
			Pref.BACK_EXIT_APP -> this@ActMain.finish()
			
			Pref.BACK_OPEN_COLUMN_LIST -> Action_App.columnList(this@ActMain)
			
			Pref.BACK_CLOSE_COLUMN -> {
				
				val closeableColumnList = getClosableColumnList()
				when(closeableColumnList.size) {
					0 -> {
						if(Pref.bpExitAppWhenCloseProtectedColumn(pref)
							&& Pref.bpDontConfirmBeforeCloseColumn(pref)
						) {
							this@ActMain.finish()
						} else {
							showToast(false, R.string.missing_closeable_column)
						}
					}
					
					1 -> {
						closeColumn(closeableColumnList.first())
					}
					
					else -> {
						showToast(
							false,
							R.string.cant_close_column_by_back_button_when_multiple_column_shown
						)
					}
				}
			}
			
			// ActAppSetting.BACK_ASK_ALWAYS
			else -> {
				
				val closeableColumnList = getClosableColumnList()
				
				val dialog = ActionsDialog()
				
				
				if(closeableColumnList.size == 1) {
					val column = closeableColumnList.first()
					dialog.addAction(getString(R.string.close_column)) {
						closeColumn(column, bConfirmed = true)
					}
				}
				
				dialog.addAction(getString(R.string.open_column_list)) { Action_App.columnList(this@ActMain) }
				dialog.addAction(getString(R.string.app_exit)) { this@ActMain.finish() }
				dialog.show(this, null)
			}
		}
	}
	
	internal fun initUI() {
		setContentView(R.layout.act_main)
		App1.initEdgeToEdge(this)
		
		quickTootVisibility =
			TootVisibility.parseSavedVisibility(Pref.spQuickTootVisibility(pref))
				?: quickTootVisibility
		
		
		Column.reloadDefaultColor(this, pref)
		
		var sv = Pref.spTimelineFont(pref)
		if(sv.isNotEmpty()) {
			try {
				timeline_font = Typeface.createFromFile(sv)
			} catch(ex : Throwable) {
				log.trace(ex)
			}
		}
		
		sv = Pref.spTimelineFontBold(pref)
		if(sv.isNotEmpty()) {
			try {
				timeline_font_bold = Typeface.createFromFile(sv)
			} catch(ex : Throwable) {
				log.trace(ex)
			}
			
		} else {
			try {
				timeline_font_bold = Typeface.create(timeline_font, Typeface.BOLD)
			} catch(ex : Throwable) {
				log.trace(ex)
			}
		}
		
		fun parseIconSize(stringPref : StringPref, minDp : Float = 1f) : Int {
			var icon_size_dp = stringPref.defVal.toFloat()
			try {
				sv = stringPref(pref)
				val fv = if(sv.isEmpty()) Float.NaN else sv.toFloat()
				if(fv.isFinite() && fv >= minDp) {
					icon_size_dp = fv
				}
			} catch(ex : Throwable) {
				log.trace(ex)
			}
			return (0.5f + icon_size_dp * density).toInt()
		}
		
		avatarIconSize = parseIconSize(Pref.spAvatarIconSize)
		notificationTlIconSize = parseIconSize(Pref.spNotificationTlIconSize)
		boostButtonSize = parseIconSize(Pref.spBoostButtonSize)
		replyIconSize = parseIconSize(Pref.spReplyIconSize)
		headerIconSize = parseIconSize(Pref.spHeaderIconSize)
		stripIconSize = parseIconSize(Pref.spStripIconSize)
		screenBottomPadding = parseIconSize(Pref.spScreenBottomPadding, minDp = 0f)
		
		run {
			var round_ratio = 33f
			try {
				if(Pref.bpDontRound(pref)) {
					round_ratio = 0f
				} else {
					sv = Pref.spRoundRatio(pref)
					if(sv.isNotEmpty()) {
						val fv = sv.toFloat()
						if(fv.isFinite()) {
							round_ratio = fv
						}
					}
				}
			} catch(ex : Throwable) {
				log.trace(ex)
			}
			Styler.round_ratio = clipRange(0f, 1f, round_ratio / 100f) * 0.5f
		}
		
		run {
			var boost_alpha = 0.8f
			try {
				val f = (Pref.spBoostAlpha.toInt(pref).toFloat() + 0.5f) / 100f
				boost_alpha = when {
					f >= 1f -> 1f
					f < 0f -> 0.66f
					else -> f
				}
			} catch(ex : Throwable) {
				log.trace(ex)
			}
			Styler.boost_alpha = boost_alpha
		}
		
		llEmpty = findViewById(R.id.llEmpty)
		
		drawer = findViewById(R.id.drawer_layout)
		drawer.addDrawerListener(this)
		
		drawer.setExclusionSize(stripIconSize)
		
		
		
		
		
		SideMenuAdapter(this, handler, findViewById(R.id.nav_view), drawer)
		
		btnMenu = findViewById(R.id.btnMenu)
		btnToot = findViewById(R.id.btnToot)
		vFooterDivider1 = findViewById(R.id.vFooterDivider1)
		vFooterDivider2 = findViewById(R.id.vFooterDivider2)
		llColumnStrip = findViewById(R.id.llColumnStrip)
		svColumnStrip = findViewById(R.id.svColumnStrip)
		llQuickTootBar = findViewById(R.id.llQuickTootBar)
		etQuickToot = findViewById(R.id.etQuickToot)
		btnQuickToot = findViewById(R.id.btnQuickToot)
		btnQuickTootMenu = findViewById(R.id.btnQuickTootMenu)
		
		val llFormRoot : LinearLayout = findViewById(R.id.llFormRoot)
		
		llFormRoot.setPadding(0, 0, 0, screenBottomPadding)
		
		etQuickToot.typeface = timeline_font
		
		when(Pref.ipJustifyWindowContentPortrait(pref)) {
			Pref.JWCP_START -> {
				val iconW = (stripIconSize * 1.5f + 0.5f).toInt()
				val padding = resources.displayMetrics.widthPixels / 2 - iconW
				
				fun ViewGroup.addViewBeforeLast(v : View) = addView(v, childCount - 1)
				(svColumnStrip.parent as LinearLayout).addViewBeforeLast(
					View(this).apply {
						layoutParams = LinearLayout.LayoutParams(padding, 0)
					}
				)
				llQuickTootBar.addViewBeforeLast(
					View(this).apply {
						layoutParams = LinearLayout.LayoutParams(padding, 0)
					}
				)
			}
			
			Pref.JWCP_END -> {
				val iconW = (stripIconSize * 1.5f + 0.5f).toInt()
				val borderWidth = (1f * density + 0.5f).toInt()
				val padding = resources.displayMetrics.widthPixels / 2 - iconW - borderWidth
				
				fun ViewGroup.addViewAfterFirst(v : View) = addView(v, 1)
				(svColumnStrip.parent as LinearLayout).addViewAfterFirst(
					View(this).apply {
						layoutParams = LinearLayout.LayoutParams(padding, 0)
					}
				)
				llQuickTootBar.addViewAfterFirst(
					View(this).apply {
						layoutParams = LinearLayout.LayoutParams(padding, 0)
					}
				)
			}
		}
		
		if(! Pref.bpQuickTootBar(pref)) {
			llQuickTootBar.visibility = View.GONE
		}
		
		btnToot.setOnClickListener(this)
		btnMenu.setOnClickListener(this)
		btnQuickToot.setOnClickListener(this)
		btnQuickTootMenu.setOnClickListener(this)
		
		if(Pref.bpDontUseActionButtonWithQuickTootBar(pref)) {
			etQuickToot.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
			etQuickToot.imeOptions = EditorInfo.IME_ACTION_NONE
			// 最後に指定する必要がある？
			etQuickToot.maxLines = 5
			etQuickToot.isVerticalScrollBarEnabled = true
			etQuickToot.isScrollbarFadingEnabled = false
		} else {
			etQuickToot.inputType = InputType.TYPE_CLASS_TEXT
			etQuickToot.imeOptions = EditorInfo.IME_ACTION_SEND
			etQuickToot.setOnEditorActionListener(TextView.OnEditorActionListener { _, actionId, _ ->
				if(actionId == EditorInfo.IME_ACTION_SEND) {
					btnQuickToot.performClick()
					return@OnEditorActionListener true
				}
				false
			})
			// 最後に指定する必要がある？
			etQuickToot.maxLines = 1
		}
		
		svColumnStrip.isHorizontalFadingEdgeEnabled = true
		
		post_helper = PostHelper(this, pref, app_state.handler)
		
		val dm = resources.displayMetrics
		
		val density = dm.density
		
		var media_thumb_height = 64
		sv = Pref.spMediaThumbHeight(pref)
		if(sv.isNotEmpty()) {
			try {
				val iv = Integer.parseInt(sv)
				if(iv >= 32) {
					media_thumb_height = iv
				}
			} catch(ex : Throwable) {
				log.trace(ex)
			}
			
		}
		app_state.media_thumb_height = (0.5f + media_thumb_height * density).toInt()
		
		var column_w_min_dp = COLUMN_WIDTH_MIN_DP
		sv = Pref.spColumnWidth(pref)
		if(sv.isNotEmpty()) {
			try {
				val iv = Integer.parseInt(sv)
				if(iv >= 100) {
					column_w_min_dp = iv
				}
			} catch(ex : Throwable) {
				log.trace(ex)
			}
			
		}
		val column_w_min = (0.5f + column_w_min_dp * density).toInt()
		
		val sw = dm.widthPixels
		
		
		if(Pref.bpDisableTabletMode(pref) || sw < column_w_min * 2) {
			// SmartPhone mode
			phoneEnv = PhoneEnv()
		} else {
			// Tablet mode
			tabletEnv = TabletEnv()
		}
		
		val tmpPhonePager : MyViewPager = findViewById(R.id.viewPager)
		val tmpTabletPager : RecyclerView = findViewById(R.id.rvPager)
		
		phoneTab({ env ->
			tmpTabletPager.visibility = View.GONE
			env.pager = tmpPhonePager
			env.pager_adapter = ColumnPagerAdapter(this)
			env.pager.adapter = env.pager_adapter
			env.pager.addOnPageChangeListener(this)
			
			resizeAutoCW(sw)
			
		}, { env ->
			tmpPhonePager.visibility = View.GONE
			env.tablet_pager = tmpTabletPager
			env.tablet_pager_adapter = TabletColumnPagerAdapter(this)
			env.tablet_layout_manager =
				LinearLayoutManager(
					this,
					LinearLayoutManager.HORIZONTAL,
					false
				)
			
			if(env.tablet_pager.itemDecorationCount == 0) {
				env.tablet_pager.addItemDecoration(TabletColumnDivider(this@ActMain))
			}
			
			
			env.tablet_pager.adapter = env.tablet_pager_adapter
			env.tablet_pager.layoutManager = env.tablet_layout_manager
			env.tablet_pager.addOnScrollListener(object :
				RecyclerView.OnScrollListener() {
				
				override fun onScrollStateChanged(
					recyclerView : RecyclerView,
					newState : Int
				) {
					super.onScrollStateChanged(recyclerView, newState)
					
					val vs = env.tablet_layout_manager.findFirstVisibleItemPosition()
					val ve = env.tablet_layout_manager.findLastVisibleItemPosition()
					// 端に近い方に合わせる
					val distance_left = abs(vs)
					val distance_right = abs(app_state.column_list.size - 1 - ve)
					if(distance_left < distance_right) {
						scrollColumnStrip(vs)
					} else {
						scrollColumnStrip(ve)
					}
				}
				
				override fun onScrolled(
					recyclerView : RecyclerView,
					dx : Int,
					dy : Int
				) {
					super.onScrolled(recyclerView, dx, dy)
					updateColumnStripSelection(- 1, - 1f)
				}
			})
			
			env.tablet_pager.itemAnimator = null
			//			val animator = env.tablet_pager.itemAnimator
			//			if( animator is DefaultItemAnimator){
			//				animator.supportsChangeAnimations = false
			//			}
			
			env.tablet_snap_helper = GravitySnapHelper(Gravity.START)
			env.tablet_snap_helper.attachToRecyclerView(env.tablet_pager)
			
		})
		
		showFooterColor()
		
		post_helper.attachEditText(
			llFormRoot,
			etQuickToot,
			true,
			object : PostHelper.Callback2 {
				override fun onTextUpdate() {}
				
				override fun canOpenPopup() : Boolean {
					return ! drawer.isDrawerOpen(GravityCompat.START)
				}
			})
		
		showQuickTootVisibility()
	}
	
	private fun isVisibleColumn(idx : Int) = phoneTab(
		{ env ->
			val c = env.pager.currentItem
			c == idx
		}, { env ->
			idx >= 0 && idx in env.visibleColumnsIndices
		}
	)
	
	private fun updateColumnStrip() {
		llEmpty.vg(app_state.column_list.isEmpty())
		
		val iconSize = stripIconSize
		val rootW = (iconSize * 1.25f + 0.5f).toInt()
		val rootH = (iconSize * 1.5f + 0.5f).toInt()
		val iconTopMargin = (iconSize * 0.125f + 0.5f).toInt()
		val barHeight = (iconSize * 0.094f + 0.5f).toInt()
		val barTopMargin = (iconSize * 0.094f + 0.5f).toInt()
		
		// 両端のメニューと投稿ボタンの大きさ
		val pad = (rootH - iconSize) shr 1
		for(btn in arrayOf(btnToot, btnMenu, btnQuickTootMenu, btnQuickToot)) {
			btn.layoutParams.width = rootH // not W
			btn.layoutParams.height = rootH
			btn.setPaddingRelative(pad, pad, pad, pad)
		}
		
		llColumnStrip.removeAllViews()
		for(i in 0 until app_state.column_list.size) {
			
			val column = app_state.column_list[i]
			
			val viewRoot = layoutInflater.inflate(R.layout.lv_column_strip, llColumnStrip, false)
			val ivIcon = viewRoot.findViewById<ImageView>(R.id.ivIcon)
			val vAcctColor = viewRoot.findViewById<View>(R.id.vAcctColor)
			
			// root: 48x48dp LinearLayout(vertical), gravity=center
			viewRoot.layoutParams.width = rootW
			viewRoot.layoutParams.height = rootH
			
			// ivIcon: 32x32dp marginTop="4dp" 図柄が32x32dp、パディングなし
			ivIcon.layoutParams.width = iconSize
			ivIcon.layoutParams.height = iconSize
			(ivIcon.layoutParams as? LinearLayout.LayoutParams)?.topMargin = iconTopMargin
			
			// vAcctColor: 32x3dp marginTop="3dp"
			vAcctColor.layoutParams.width = iconSize
			vAcctColor.layoutParams.height = barHeight
			(vAcctColor.layoutParams as? LinearLayout.LayoutParams)?.topMargin = barTopMargin
			
			viewRoot.tag = i
			viewRoot.setOnClickListener { v ->
				val idx = v.tag as Int
				if(Pref.bpScrollTopFromColumnStrip(pref) && isVisibleColumn(idx)) {
					app_state.column_list[i].viewHolder?.scrollToTop2()
					return@setOnClickListener
				}
				scrollToColumn(idx)
			}
			viewRoot.contentDescription = column.getColumnName(true)
			
			viewRoot.backgroundDrawable = getAdaptiveRippleDrawableRound(
				this,
				column.getHeaderBackgroundColor(),
				column.getHeaderNameColor()
			)
			
			ivIcon.setImageResource(column.getIconId())
			ivIcon.imageTintList = ColorStateList.valueOf(column.getHeaderNameColor())
			
			//
			val ac = AcctColor.load(column.access_info)
			if(AcctColor.hasColorForeground(ac)) {
				vAcctColor.setBackgroundColor(ac.color_fg)
			} else {
				vAcctColor.visibility = View.INVISIBLE
			}
			
			//
			llColumnStrip.addView(viewRoot)
		}
		svColumnStrip.requestLayout()
		updateColumnStripSelection(- 1, - 1f)
		
	}
	
	private fun updateColumnStripSelection(position : Int, positionOffset : Float) {
		handler.post(Runnable {
			if(isFinishing) return@Runnable
			
			if(app_state.column_list.isEmpty()) {
				llColumnStrip.setVisibleRange(- 1, - 1, 0f)
			} else {
				phoneTab({ env ->
					if(position >= 0) {
						llColumnStrip.setVisibleRange(position, position, positionOffset)
					} else {
						val c = env.pager.currentItem
						llColumnStrip.setVisibleRange(c, c, 0f)
					}
					
				}, { env ->
					val vs = env.tablet_layout_manager.findFirstVisibleItemPosition()
					val ve = env.tablet_layout_manager.findLastVisibleItemPosition()
					val vr = if(vs == RecyclerView.NO_POSITION || ve == RecyclerView.NO_POSITION) {
						IntRange(- 1, - 2) // empty and less than zero
					} else {
						IntRange(vs, min(ve, vs + nScreenColumn - 1))
					}
					var slide_ratio = 0f
					if(vr.first <= vr.last) {
						val child = env.tablet_layout_manager.findViewByPosition(vr.first)
						slide_ratio =
							clipRange(0f, 1f, abs((child?.left ?: 0) / nColumnWidth.toFloat()))
					}
					
					llColumnStrip.setVisibleRange(vr.first, vr.last, slide_ratio)
				})
			}
		})
	}
	
	private fun scrollColumnStrip(select : Int) {
		val child_count = llColumnStrip.childCount
		if(select < 0 || select >= child_count) {
			return
		}
		
		val icon = llColumnStrip.getChildAt(select)
		
		val sv_width = (llColumnStrip.parent as View).width
		val ll_width = llColumnStrip.width
		val icon_width = icon.width
		val icon_left = icon.left
		
		if(sv_width == 0 || ll_width == 0 || icon_width == 0) {
			handler.postDelayed({ scrollColumnStrip(select) }, 20L)
		}
		
		val sx = icon_left + icon_width / 2 - sv_width / 2
		svColumnStrip.smoothScrollTo(sx, 0)
		
	}
	
	// ActOAuthCallbackで受け取ったUriを処理する
	private fun handleIntentUri(uri : Uri) {
		
		log.d("handleIntentUri ${uri}")
		
		when(uri.scheme) {
			"subwaytooter", "misskeyclientproto" -> return try {
				handleOAuth2CallbackUri(uri)
			} catch(ex : Throwable) {
				log.trace(ex)
			}
		}
		
		val url = uri.toString()
		
		val statusInfo = url.findStatusIdFromUrl()
		if(statusInfo != null) {
			// ステータスをアプリ内で開く
			Action_Toot.conversationOtherInstance(
				this@ActMain,
				defaultInsertPosition,
				statusInfo.url,
				statusInfo.statusId,
				statusInfo.host,
				statusInfo.statusId
			)
			return
		}
		
		// ユーザページをアプリ内で開く
		var m = TootAccount.reAccountUrl.matcher(url)
		if(m.find()) {
			val host = m.groupEx(1) !!
			val user = m.groupEx(2) !!.decodePercent()
			val instance = m.groupEx(3)?.decodePercent()
			
			if(instance?.isNotEmpty() == true) {
				Action_User.profile(
					this@ActMain,
					defaultInsertPosition,
					null,
					"https://$instance/@$user",
					Host.parse(instance),
					user,
					original_url = url
				)
			} else {
				Action_User.profile(
					this@ActMain,
					defaultInsertPosition,
					null,
					url,
					Host.parse(host),
					user
				)
			}
			return
		}
		
		// intentFilterの都合でこの形式のURLが飛んでくることはないのだが…。
		m = TootAccount.reAccountUrl2.matcher(url)
		if(m.find()) {
			val host = m.groupEx(1) !!
			val user = m.groupEx(2) !!.decodePercent()
			
			Action_User.profile(
				this@ActMain,
				defaultInsertPosition,
				null,
				url,
				Host.parse(host),
				user
			)
			return
		}
		
		// このアプリでは処理できないURLだった
		// 外部ブラウザを開きなおそうとすると無限ループの恐れがある
		// アプリケーションチューザーを表示する
		
		val error_message = getString(R.string.cant_handle_uri_of, url)
		
		try {
			val query_flag = if(Build.VERSION.SDK_INT >= 23) {
				// Android 6.0以降
				// MATCH_DEFAULT_ONLY だと標準の設定に指定されたアプリがあるとソレしか出てこない
				// MATCH_ALL を指定すると 以前と同じ挙動になる
				PackageManager.MATCH_ALL
			} else {
				// Android 5.xまでは MATCH_DEFAULT_ONLY でマッチするすべてのアプリを取得できる
				PackageManager.MATCH_DEFAULT_ONLY
			}
			
			// queryIntentActivities に渡すURLは実在しないホストのものにする
			val intent = Intent(Intent.ACTION_VIEW, "https://dummy.subwaytooter.club/".toUri())
			intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
			
			val my_name = packageName
			val resolveInfoList = packageManager.queryIntentActivities(intent, query_flag)
				.filter { my_name != it.activityInfo.packageName }
			
			if(resolveInfoList.isEmpty()) {
				throw RuntimeException("resolveInfoList is empty.")
			}
			
			// このアプリ以外の選択肢を集める
			val choice_list = resolveInfoList
				.map {
					Intent(Intent.ACTION_VIEW, uri).apply {
						addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
						`package` = it.activityInfo.packageName
						setClassName(it.activityInfo.packageName, it.activityInfo.name)
					}
				}.toMutableList()
			
			val chooser = Intent.createChooser(choice_list.removeAt(0), error_message)
			// 2つめ以降はEXTRAに渡す
			chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, choice_list.toTypedArray())
			
			// 指定した選択肢でチューザーを作成して開く
			startActivity(chooser)
			return
		} catch(ex : Throwable) {
			log.trace(ex)
		}
		
		AlertDialog.Builder(this)
			.setCancelable(true)
			.setMessage(error_message)
			.setPositiveButton(R.string.close, null)
			.show()
		
	}
	
	private fun handleOAuth2CallbackUri(uri : Uri) {
		
		// 通知タップ
		// subwaytooter://notification_click/?db_id=(db_id)
		val dataIdString = uri.getQueryParameter("db_id")
		if(dataIdString != null) {
			
			PollingWorker.queueNotificationClicked(this, uri)
			
			try {
				val dataId = dataIdString.toLong()
				val account = SavedAccount.loadAccount(this@ActMain, dataId)
				if(account != null) {
					var column = app_state.column_list.firstOrNull {
						it.type == ColumnType.NOTIFICATIONS
							&& account == it.access_info
							&& ! it.system_notification_not_related
					}
					if(column != null) {
						val index = app_state.column_list.indexOf(column)
						scrollToColumn(index)
					} else {
						column = addColumn(
							true,
							defaultInsertPosition,
							account,
							ColumnType.NOTIFICATIONS
						)
					}
					// 通知を読み直す
					if(! column.bInitialLoading) {
						column.startLoading()
					}
				}
			} catch(ex : Throwable) {
				log.trace(ex)
			}
			
			return
		}
		
		// OAuth2 認証コールバック
		// subwaytooter://oauth(\d*)/?...
		TootTaskRunner(this@ActMain).run(object : TootTask {
			
			var ta : TootAccount? = null
			var sa : SavedAccount? = null
			var host : Host? = null
			var ti : TootInstance? = null
			
			override fun background(client : TootApiClient) : TootApiResult? {
				
				val uriStr = uri.toString()
				if(uriStr.startsWith("subwaytooter://misskey/auth_callback")
					|| uriStr.startsWith("misskeyclientproto://misskeyclientproto/auth_callback")
				) {
					
					// Misskey 認証コールバック
					val token = uri.getQueryParameter("token")
					if(token?.isEmpty() != false) {
						return TootApiResult("missing token in callback URL")
					}
					val prefDevice = PrefDevice.prefDevice(this@ActMain)
					
					val db_id = prefDevice.getLong(PrefDevice.LAST_AUTH_DB_ID, - 1L)
					
					val instance = Host.parse(
						prefDevice.getString(PrefDevice.LAST_AUTH_INSTANCE, null)
							?: return TootApiResult("missing instance name.")
					)
					
					if(db_id != - 1L) {
						try {
							val sa = SavedAccount.loadAccount(this@ActMain, db_id)
								?: return TootApiResult("missing account db_id=$db_id")
							this.sa = sa
							client.account = sa
						} catch(ex : Throwable) {
							log.trace(ex)
							return TootApiResult(ex.withCaption("invalid state"))
						}
					} else {
						client.apiHost = instance
					}
					
					val (ti, r2) = TootInstance.get(client)
					ti ?: return r2
					
					this.ti = ti
					this.host = instance
					val client_name = Pref.spClientName(this@ActMain)
					val result =
						client.authentication2Misskey(client_name, token, ti.misskeyVersion)
					this.ta = TootParser(
						this@ActMain,
						linkHelper = LinkHelper.create(
							instance,
							misskeyVersion = ti.misskeyVersion
						)
					).account(result?.jsonObject)
					return result
					
				} else {
					// Mastodon 認証コールバック
					
					// エラー時
					// subwaytooter://oauth(\d*)/
					// ?error=access_denied
					// &error_description=%E3%83%AA%E3%82%BD%E3%83%BC%E3%82%B9%E3%81%AE%E6%89%80%E6%9C%89%E8%80%85%E3%81%BE%E3%81%9F%E3%81%AF%E8%AA%8D%E8%A8%BC%E3%82%B5%E3%83%BC%E3%83%90%E3%83%BC%E3%81%8C%E8%A6%81%E6%B1%82%E3%82%92%E6%8B%92%E5%90%A6%E3%81%97%E3%81%BE%E3%81%97%E3%81%9F%E3%80%82
					// &state=db%3A3
					val error = uri.getQueryParameter("error_description")
					if(error?.isNotEmpty() == true) {
						return TootApiResult(error)
					}
					
					// subwaytooter://oauth(\d*)/
					//    ?code=113cc036e078ac500d3d0d3ad345cd8181456ab087abc67270d40f40a4e9e3c2
					//    &state=host%3Amastodon.juggler.jp
					
					val code = uri.getQueryParameter("code")
					if(code?.isEmpty() != false) {
						return TootApiResult("missing code in callback url.")
					}
					
					val sv = uri.getQueryParameter("state")
					if(sv?.isEmpty() != false) {
						return TootApiResult("missing state in callback url.")
					}
					
					for(param in sv.split(",")) {
						when {
							
							param.startsWith("db:") -> try {
								val dataId = param.substring(3).toLong(10)
								val sa = SavedAccount.loadAccount(this@ActMain, dataId)
									?: return TootApiResult("missing account db_id=$dataId")
								this.sa = sa
								client.account = sa
							} catch(ex : Throwable) {
								log.trace(ex)
								return TootApiResult(ex.withCaption("invalid state"))
							}
							
							param.startsWith("host:") -> {
								val host = Host.parse(param.substring(5))
								client.apiHost = host
							}
							
							else -> {
								// ignore other parameter
							}
						}
					}
					
					val instance = client.apiHost
						?: return TootApiResult("missing instance in callback url.")
					
					val (ti, r2) = TootInstance.get(client)
					ti ?: return r2
					
					this.ti = ti
					this.host = instance
					val client_name = Pref.spClientName(this@ActMain)
					val result = client.authentication2(client_name, code)
					this.ta = TootParser(
						this@ActMain,
						linkHelper = LinkHelper.create(instance)
					).account(result?.jsonObject)
					return result
				}
				
			}
			
			override fun handleResult(result : TootApiResult?) {
				val host = this.host
				val ta = this.ta
				var sa = this.sa
				
				if(ta != null && host?.isValid == true && sa == null) {
					val acct = Acct.parse(ta.username, host)
					// アカウント追加時に、アプリ内に既にあるアカウントと同じものを登録していたかもしれない
					sa = SavedAccount.loadAccountByAcct(this@ActMain, acct.ascii)
				}
				
				afterAccountVerify(result, ta, sa, ti, host)
			}
			
		})
	}
	
	internal fun afterAccountVerify(
		result : TootApiResult?,
		ta : TootAccount?,
		sa : SavedAccount?,
		ti : TootInstance?,
		host : Host?
	) : Boolean {
		
		val jsonObject = result?.jsonObject
		val token_info = result?.tokenInfo
		val error = result?.error
		
		when {
			result == null -> {
				// cancelled.
			}
			
			error != null ->
				showToast(true, "${result.error} ${result.requestInfo}".trim())
			
			token_info == null -> showToast(true, "can't get access token.")
			
			jsonObject == null -> showToast(true, "can't parse json response.")
			
			// 自分のユーザネームを取れなかった
			// …普通はエラーメッセージが設定されてるはずだが
			ta == null -> showToast(true, "can't verify user credential.")
			
			// アクセストークン更新時
			// インスタンスは同じだと思うが、ユーザ名が異なる可能性がある
			sa != null ->
				if(sa.username != ta.username) {
					showToast(true, R.string.user_name_not_match)
				} else {
					showToast(
						
						false,
						R.string.access_token_updated_for,
						sa.acct.pretty
					)
					
					// DBの情報を更新する
					sa.updateTokenInfo(token_info)
					
					// 各カラムの持つアカウント情報をリロードする
					reloadAccountSetting()
					
					// 自動でリロードする
					for(it in app_state.column_list) {
						if(it.access_info == sa) {
							it.startLoading()
						}
					}
					
					// 通知の更新が必要かもしれない
					PushSubscriptionHelper.clearLastCheck(sa)
					PollingWorker.queueUpdateNotification(this@ActMain)
					return true
				}
			
			host != null -> {
				// アカウント追加時
				val user = Acct.parse(ta.username, host)
				
				val apDomain = ti?.uri
				if(apDomain == null) {
					showToast(false, "Can't get ActivityPub domain name.")
					return false
				}
				
				val row_id = SavedAccount.insert(
					acct = user.ascii,
					host = host.ascii,
					domain = apDomain,
					account = jsonObject,
					token = token_info,
					misskeyVersion = TootInstance.parseMisskeyVersion(token_info)
				)
				val account = SavedAccount.loadAccount(this@ActMain, row_id)
				if(account != null) {
					var bModified = false
					
					if(account.loginAccount?.locked == true) {
						bModified = true
						account.visibility = TootVisibility.PrivateFollowers
					}
					if(! account.isMisskey) {
						val source = ta.source
						if(source != null) {
							val privacy = TootVisibility.parseMastodon(source.privacy)
							if(privacy != null) {
								bModified = true
								account.visibility = privacy
							}
							
							// XXX ta.source.sensitive パラメータを読んで「添付画像をデフォルトでNSFWにする」を実現する
							// 現在、アカウント設定にはこの項目はない( 「NSFWな添付メディアを隠さない」はあるが全く別の効果)
						}
						
						if(bModified) {
							account.saveSetting()
						}
					}
					
					showToast(false, R.string.account_confirmed)
					
					// 通知の更新が必要かもしれない
					PollingWorker.queueUpdateNotification(this@ActMain)
					
					// 適当にカラムを追加する
					val count = SavedAccount.count
					if(count > 1) {
						addColumn(false, defaultInsertPosition, account, ColumnType.HOME)
					} else {
						addColumn(false, defaultInsertPosition, account, ColumnType.HOME)
						addColumn(false, defaultInsertPosition, account, ColumnType.NOTIFICATIONS)
						addColumn(false, defaultInsertPosition, account, ColumnType.LOCAL)
						addColumn(false, defaultInsertPosition, account, ColumnType.FEDERATE)
					}
					
					return true
				}
			}
		}
		return false
	}
	
	// アクセストークンを手動で入力した場合
	fun checkAccessToken(
		dialog_host : Dialog?,
		dialog_token : Dialog?,
		apiHost : Host,
		access_token : String,
		sa : SavedAccount?
	) {
		
		TootTaskRunner(this@ActMain).run(apiHost, object : TootTask {
			
			var ta : TootAccount? = null
			var ti : TootInstance? = null
			
			override fun background(client : TootApiClient) : TootApiResult? {
				
				val (instance, instanceResult) = TootInstance.get(client, apiHost)
				instance ?: return instanceResult
				this.ti = instance
				
				val misskeyVersion = instance.misskeyVersion
				
				val result = client.getUserCredential(access_token, misskeyVersion = misskeyVersion)
				
				this.ta = TootParser(
					this@ActMain,
					LinkHelper.create(
						apiHost,
						apDomainArg = instance.uri?.let { Host.parse(it) },
						misskeyVersion = misskeyVersion
					)
				).account(result?.jsonObject)
				
				return result
			}
			
			override fun handleResult(result : TootApiResult?) {
				if(afterAccountVerify(result, ta, sa, ti, apiHost)) {
					dialog_host?.dismissSafe()
					dialog_token?.dismissSafe()
				}
			}
		})
	}
	
	// アクセストークンの手動入力(更新)
	private fun checkAccessToken2(db_id : Long) {
		
		val sa = SavedAccount.loadAccount(this, db_id) ?: return
		
		DlgTextInput.show(
			this,
			getString(R.string.access_token_or_api_token),
			null,
			callback = object : DlgTextInput.Callback {
				override fun onOK(dialog : Dialog, text : String) {
					checkAccessToken(null, dialog, sa.apiHost, text, sa)
				}
				
				override fun onEmptyError() {
					showToast(true, R.string.token_not_specified)
				}
			})
	}
	
	private fun reloadAccountSetting() {
		val done_list = ArrayList<SavedAccount>()
		for(column in app_state.column_list) {
			val a = column.access_info
			if(done_list.contains(a)) continue
			done_list.add(a)
			if(! a.isNA) a.reloadSetting(this@ActMain)
			column.fireShowColumnHeader()
		}
	}
	
	fun reloadAccountSetting(account : SavedAccount) {
		val done_list = ArrayList<SavedAccount>()
		for(column in app_state.column_list) {
			val a = column.access_info
			if(a != account) continue
			if(done_list.contains(a)) continue
			done_list.add(a)
			if(! a.isNA) a.reloadSetting(this@ActMain)
			column.fireShowColumnHeader()
		}
	}
	
	fun closeColumn(column : Column, bConfirmed : Boolean = false) {
		
		if(column.dont_close) {
			showToast(false, R.string.column_has_dont_close_option)
			return
		}
		
		if(! bConfirmed && ! Pref.bpDontConfirmBeforeCloseColumn(pref)) {
			AlertDialog.Builder(this)
				.setMessage(R.string.confirm_close_column)
				.setNegativeButton(R.string.cancel, null)
				.setPositiveButton(R.string.ok) { _, _ -> closeColumn(column, bConfirmed = true) }
				.show()
			return
		}
		
		val page_delete = app_state.column_list.indexOf(column)
		
		phoneTab({ env ->
			val page_showing = env.pager.currentItem
			
			removeColumn(column)
			
			if(app_state.column_list.isNotEmpty() && page_delete > 0 && page_showing == page_delete) {
				val idx = page_delete - 1
				scrollToColumn(idx)
				val c = app_state.column_list[idx]
				if(! c.bFirstInitialized) {
					c.startLoading()
				}
			}
			
		}, {
			removeColumn(column)
			
			if(app_state.column_list.isNotEmpty() && page_delete > 0) {
				val idx = page_delete - 1
				scrollToColumn(idx)
				val c = app_state.column_list[idx]
				if(! c.bFirstInitialized) {
					c.startLoading()
				}
			}
		})
	}
	
	fun closeColumnAll(
		_lastColumnIndex : Int = - 1,
		bConfirmed : Boolean = false
	) {
		
		if(! bConfirmed) {
			AlertDialog.Builder(this)
				.setMessage(R.string.confirm_close_column_all)
				.setNegativeButton(R.string.cancel, null)
				.setPositiveButton(R.string.ok) { _, _ -> closeColumnAll(_lastColumnIndex, true) }
				.show()
			return
		}
		
		var lastColumnIndex = when(_lastColumnIndex) {
			- 1 -> phoneTab(
				{ it.pager.currentItem },
				{ 0 }
			)
			else -> _lastColumnIndex
		}
		
		phoneOnly { env -> env.pager.adapter = null }
		
		for(i in (0 until app_state.column_list.size).reversed()) {
			val column = app_state.column_list[i]
			if(column.dont_close) continue
			app_state.column_list.removeAt(i).dispose()
			if(lastColumnIndex >= i) -- lastColumnIndex
		}
		
		phoneTab(
			{ env -> env.pager.adapter = env.pager_adapter },
			{ env -> resizeColumnWidth(env) }
		)
		
		app_state.saveColumnList()
		updateColumnStrip()
		
		if(app_state.column_list.isNotEmpty() && lastColumnIndex >= 0 && lastColumnIndex < app_state.column_list.size) {
			scrollToColumn(lastColumnIndex)
			val c = app_state.column_list[lastColumnIndex]
			if(! c.bFirstInitialized) {
				c.startLoading()
			}
		}
	}
	
	//////////////////////////////////////////////////////////////
	// カラム追加系
	
	fun addColumn(
		indexArg : Int,
		ai : SavedAccount,
		type : ColumnType,
		vararg params : Any
	) : Column {
		return addColumn(
			Pref.bpAllowColumnDuplication(pref),
			indexArg,
			ai,
			type,
			*params
		)
	}
	
	fun addColumn(
		allowColumnDuplication : Boolean,
		indexArg : Int,
		ai : SavedAccount,
		type : ColumnType,
		vararg params : Any
	) : Column {
		if(! allowColumnDuplication) {
			// 既に同じカラムがあればそこに移動する
			for(column in app_state.column_list) {
				if(column.isSameSpec(ai, type, params)) {
					val indexColumn = app_state.column_list.indexOf(column)
					scrollToColumn(indexColumn)
					return column
				}
			}
		}
		//
		val col = Column(app_state, ai, this, type.id, *params)
		val index = addColumn(col, indexArg)
		scrollToColumn(index)
		if(! col.bFirstInitialized) {
			col.startLoading()
		}
		return col
	}
	
	fun showColumnMatchAccount(account : SavedAccount) {
		for(column in app_state.column_list) {
			if(account == column.access_info) {
				column.fireRebindAdapterItems()
			}
		}
	}
	
	private fun showFooterColor() {
		
		val footer_button_bg_color = Pref.ipFooterButtonBgColor(pref)
		val footer_button_fg_color = Pref.ipFooterButtonFgColor(pref)
		val footer_tab_bg_color = Pref.ipFooterTabBgColor(pref)
		val footer_tab_divider_color = Pref.ipFooterTabDividerColor(pref)
		val footer_tab_indicator_color = Pref.ipFooterTabIndicatorColor(pref)
		
		val colorColumnStripBackground = footer_tab_bg_color.notZero()
			?: getAttributeColor(R.attr.colorColumnStripBackground)
		
		svColumnStrip.setBackgroundColor(colorColumnStripBackground)
		llQuickTootBar.setBackgroundColor(colorColumnStripBackground)
		
		val colorButtonBg = footer_button_bg_color.notZero()
			?: colorColumnStripBackground
		
		val colorButtonFg = footer_button_fg_color.notZero()
			?: getAttributeColor(R.attr.colorRippleEffect)
		
		btnMenu.backgroundDrawable =
			getAdaptiveRippleDrawableRound(this, colorButtonBg, colorButtonFg)
		btnToot.backgroundDrawable =
			getAdaptiveRippleDrawableRound(this, colorButtonBg, colorButtonFg)
		btnQuickToot.backgroundDrawable =
			getAdaptiveRippleDrawableRound(this, colorButtonBg, colorButtonFg)
		btnQuickTootMenu.backgroundDrawable =
			getAdaptiveRippleDrawableRound(this, colorButtonBg, colorButtonFg)
		
		val csl = ColorStateList.valueOf(
			footer_button_fg_color.notZero()
				?: getAttributeColor(R.attr.colorVectorDrawable)
		)
		btnToot.imageTintList = csl
		btnMenu.imageTintList = csl
		btnQuickToot.imageTintList = csl
		btnQuickTootMenu.imageTintList = csl
		
		val c = footer_tab_divider_color.notZero()
			?: colorColumnStripBackground
		vFooterDivider1.setBackgroundColor(c)
		vFooterDivider2.setBackgroundColor(c)
		
		llColumnStrip.indicatorColor = footer_tab_indicator_color.notZero()
			?: getAttributeColor(R.attr.colorAccent)
	}
	
	/////////////////////////////////////////////////////////////////////////
	// タブレット対応で必要になった関数など
	
	private fun closeColumnSetting() : Boolean {
		phoneTab({ env ->
			val vh = env.pager_adapter.getColumnViewHolder(env.pager.currentItem)
			if(vh?.isColumnSettingShown == true) {
				vh.showColumnSetting(false)
				return@closeColumnSetting true
			}
		}, { env ->
			for(i in 0 until env.tablet_layout_manager.childCount) {
				
				val columnViewHolder = when(val v = env.tablet_layout_manager.getChildAt(i)) {
					null -> null
					else -> (env.tablet_pager.getChildViewHolder(v) as? TabletColumnViewHolder)?.columnViewHolder
				}
				
				if(columnViewHolder?.isColumnSettingShown == true) {
					columnViewHolder.showColumnSetting(false)
					return@closeColumnSetting true
				}
			}
		})
		return false
	}
	
	private fun addColumn(column : Column, indexArg : Int) : Int {
		var index = indexArg
		val size = app_state.column_list.size
		if(index > size) index = size
		
		phoneOnly { env -> env.pager.adapter = null }
		
		app_state.column_list.add(index, column)
		
		phoneTab(
			{ env -> env.pager.adapter = env.pager_adapter },
			{ env -> resizeColumnWidth(env) }
		)
		
		app_state.saveColumnList()
		updateColumnStrip()
		
		return index
	}
	
	private fun removeColumn(column : Column) {
		val idx_column = app_state.column_list.indexOf(column)
		if(idx_column == - 1) return
		
		phoneOnly { env -> env.pager.adapter = null }
		
		app_state.column_list.removeAt(idx_column).dispose()
		
		phoneTab(
			{ env -> env.pager.adapter = env.pager_adapter },
			{ env -> resizeColumnWidth(env) }
		)
		
		app_state.saveColumnList()
		updateColumnStrip()
	}
	
	private fun setOrder(new_order : ArrayList<Int>) {
		
		phoneOnly { env -> env.pager.adapter = null }
		
		val ie = app_state.column_list.size
		
		val tmp_list = ArrayList<Column>()
		val used_set = HashSet<Int>()
		
		// copy by new_order
		for(i in new_order) {
			if(0 <= i && i < ie) {
				used_set.add(i)
				tmp_list.add(app_state.column_list[i])
			}
		}
		
		// dispose unused elements.
		for(i in 0 until ie) {
			if(used_set.contains(i)) continue
			app_state.column_list[i].dispose()
		}
		app_state.column_list.clear()
		app_state.column_list.addAll(tmp_list)
		
		phoneTab(
			{ env -> env.pager.adapter = env.pager_adapter },
			{ env -> resizeColumnWidth(env) }
		)
		
		app_state.saveColumnList()
		updateColumnStrip()
	}
	
	private fun resizeColumnWidth(env : TabletEnv) {
		
		var column_w_min_dp = COLUMN_WIDTH_MIN_DP
		val sv = Pref.spColumnWidth(pref)
		if(sv.isNotEmpty()) {
			try {
				val iv = Integer.parseInt(sv)
				if(iv >= 100) {
					column_w_min_dp = iv
				}
			} catch(ex : Throwable) {
				log.trace(ex)
			}
			
		}
		
		val dm = resources.displayMetrics
		
		val screen_width = dm.widthPixels
		
		val density = dm.density
		var column_w_min = (0.5f + column_w_min_dp * density).toInt()
		if(column_w_min < 1) column_w_min = 1
		
		var column_w : Int
		
		if(screen_width < column_w_min * 2) {
			// 最小幅で2つ表示できないのなら1カラム表示
			nScreenColumn = 1
			column_w = screen_width
		} else {
			
			// カラム最小幅から計算した表示カラム数
			nScreenColumn = screen_width / column_w_min
			if(nScreenColumn < 1) nScreenColumn = 1
			
			// データのカラム数より大きくならないようにする
			// (でも最小は1)
			val column_count = app_state.column_list.size
			if(column_count > 0 && column_count < nScreenColumn) {
				nScreenColumn = column_count
			}
			
			// 表示カラム数から計算したカラム幅
			column_w = screen_width / nScreenColumn
			
			// 最小カラム幅の1.5倍よりは大きくならないようにする
			val column_w_max = (0.5f + column_w_min * 1.5f).toInt()
			if(column_w > column_w_max) {
				column_w = column_w_max
			}
		}
		
		nColumnWidth = column_w // dividerの幅を含む
		
		val divider_width = (0.5f + 1f * density).toInt()
		column_w -= divider_width
		env.tablet_pager_adapter.columnWidth = column_w // dividerの幅を含まない
		// env.tablet_snap_helper.columnWidth = column_w //使われていない
		
		resizeAutoCW(column_w) // dividerの幅を含まない
		
		// 並べ直す
		env.tablet_pager_adapter.notifyDataSetChanged()
	}
	
	private fun scrollToColumn(index : Int, smoothScroll : Boolean = true) {
		scrollColumnStrip(index)
		phoneTab(
			
			// スマホはスムーススクロール基本ありだがたまにしない
			{ env ->
				log.d("ipLastColumnPos beforeScroll=${env.pager.currentItem}")
				env.pager.setCurrentItem(index, smoothScroll)
			},
			
			// タブレットでスムーススクロールさせると頻繁にオーバーランするので絶対しない
			{ env ->
				log.d("ipLastColumnPos beforeScroll=${env.visibleColumnsIndices.first}")
				env.tablet_pager.scrollToPosition(index)
			}
		)
	}
	
	//////////////////////////////////////////////////////////////////////////////////////////////
	
	@Suppress("BlockingMethodInNonBlockingContext")
	private fun importAppData(uri : Uri?) {
		uri ?: return
		
		// remove all columns
		phoneOnly { env -> env.pager.adapter = null }
		
		for(c in app_state.column_list) {
			c.dispose()
		}
		app_state.column_list.clear()
		
		phoneTab(
			{ env -> env.pager.adapter = env.pager_adapter },
			{ env -> resizeColumnWidth(env) }
		)
		
		updateColumnStrip()
		
		
		runWithProgress(
			"importing app data",
			
			doInBackground = { progress ->
				fun setProgressMessage(sv : String) =
					runOnMainLooper { progress.setMessageEx(sv) }
				
				var newColumnList : ArrayList<Column>? = null
				
				setProgressMessage("import data to local storage...")
				
				// アプリ内領域に一時ファイルを作ってコピーする
				val cacheDir = cacheDir
				cacheDir.mkdir()
				val file = File(
					cacheDir,
					"SubwayTooter.${Process.myPid()}.${Process.myTid()}.tmp"
				)
				val source = contentResolver.openInputStream(uri)
				if(source == null) {
					showToast(true, "openInputStream failed.")
					return@runWithProgress null
				}
				source.use { inStream ->
					FileOutputStream(file).use { outStream ->
						IOUtils.copy(inStream, outStream)
					}
				}
				
				// 通知サービスを止める
				setProgressMessage("syncing notification poller…")
				PollingWorker.queueAppDataImportBefore(this@ActMain)
				while(PollingWorker.mBusyAppDataImportBefore.get()) {
					delay(1000L)
					log.d("syncing polling task...")
				}
				
				// データを読み込む
				setProgressMessage("reading app data...")
				var zipEntryCount = 0
				try {
					ZipInputStream(FileInputStream(file)).use { zipStream ->
						while(true) {
							val entry = zipStream.nextEntry ?: break
							++ zipEntryCount
							try {
								//
								val entryName = entry.name
								if(entryName.endsWith(".json")) {
									newColumnList = AppDataExporter.decodeAppData(
										this@ActMain,
										JsonReader(InputStreamReader(zipStream, "UTF-8"))
									)
									continue
								}
								
								if(AppDataExporter.restoreBackgroundImage(
										this@ActMain,
										newColumnList,
										zipStream,
										entryName
									)
								) {
									continue
								}
							} finally {
								zipStream.closeEntry()
							}
						}
					}
				} catch(ex : Throwable) {
					log.trace(ex)
					if(zipEntryCount != 0) {
						showToast(ex, "importAppData failed.")
					}
				}
				// zipではなかった場合、zipEntryがない状態になる。例外はPH-1では出なかったが、出ても問題ないようにする。
				if(zipEntryCount == 0) {
					InputStreamReader(FileInputStream(file), "UTF-8").use { inStream ->
						newColumnList = AppDataExporter.decodeAppData(
							this@ActMain,
							JsonReader(inStream)
						)
					}
				}
				
				newColumnList
			},
			afterProc = {
				// cancelled.
				if(it == null) return@runWithProgress
				
				try {
					phoneOnly { env -> env.pager.adapter = null }
					
					app_state.column_list.clear()
					app_state.column_list.addAll(it)
					app_state.saveColumnList()
					
					phoneTab(
						{ env -> env.pager.adapter = env.pager_adapter },
						{ env -> resizeColumnWidth(env) }
					)
					updateColumnStrip()
				} finally {
					// 通知サービスをリスタート
					PollingWorker.queueAppDataImportAfter(this@ActMain)
				}
				
				showToast(true, R.string.import_completed_please_restart_app)
				finish()
			},
			preProc = {
				window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
			},
			postProc = {
				window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
			}
		)
	}
	
	override fun onDrawerSlide(drawerView : View, slideOffset : Float) {
		post_helper.closeAcctPopup()
	}
	
	override fun onDrawerOpened(drawerView : View) {
		post_helper.closeAcctPopup()
	}
	
	override fun onDrawerClosed(drawerView : View) {
		post_helper.closeAcctPopup()
	}
	
	override fun onDrawerStateChanged(newState : Int) {
		post_helper.closeAcctPopup()
	}
	
	private fun resizeAutoCW(column_w : Int) {
		val sv = Pref.spAutoCWLines(pref)
		nAutoCwLines = sv.optInt() ?: - 1
		if(nAutoCwLines > 0) {
			val lv_pad = (0.5f + 12 * density).toInt()
			val icon_width = avatarIconSize
			val icon_end = (0.5f + 4 * density).toInt()
			nAutoCwCellWidth = column_w - lv_pad * 2 - icon_width - icon_end
		}
		// この後各カラムは再描画される
	}
	
	fun checkAutoCW(status : TootStatus, text : CharSequence) {
		if(nAutoCwCellWidth <= 0) {
			// 設定が無効
			status.auto_cw = null
			return
		}
		
		var auto_cw = status.auto_cw
		if(auto_cw != null &&
			auto_cw.refActivity?.get() === this@ActMain &&
			auto_cw.cell_width == nAutoCwCellWidth
		) {
			// 以前に計算した値がまだ使える
			return
		}
		
		if(auto_cw == null) {
			auto_cw = TootStatus.AutoCW()
			status.auto_cw = auto_cw
		}
		
		// 計算時の条件(文字フォント、文字サイズ、カラム幅）を覚えておいて、再利用時に同じか確認する
		auto_cw.refActivity = WeakReference(this@ActMain)
		auto_cw.cell_width = nAutoCwCellWidth
		auto_cw.decoded_spoiler_text = null
		
		// テキストをレイアウトして行数を測定
		val tv = TextView(this).apply {
			layoutParams =
				LinearLayout.LayoutParams(nAutoCwCellWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
			if(! timeline_font_size_sp.isNaN())
				textSize = timeline_font_size_sp
			
			val fv = timeline_spacing
			if(fv != null) setLineSpacing(0f, fv)
			
			typeface = timeline_font
			this.text = text
		}
		
		tv.measure(
			View.MeasureSpec.makeMeasureSpec(nAutoCwCellWidth, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
		)
		val l = tv.layout
		if(l != null) {
			auto_cw.originalLineCount = l.lineCount
			val line_count = auto_cw.originalLineCount
			
			if((nAutoCwLines > 0 && line_count > nAutoCwLines)
				&& status.spoiler_text.isEmpty()
				&& (status.mentions?.size ?: 0) <= nAutoCwLines
			) {
				val sb = SpannableStringBuilder()
				sb.append(getString(R.string.auto_cw_prefix))
				sb.append(text, 0, l.getLineEnd(nAutoCwLines - 1))
				var last = sb.length
				while(last > 0) {
					val c = sb[last - 1]
					if(c == '\n' || Character.isWhitespace(c)) {
						-- last
						continue
					}
					break
				}
				if(last < sb.length) {
					sb.delete(last, sb.length)
				}
				sb.append('…')
				auto_cw.decoded_spoiler_text = sb
			}
		}
	}
	
	private fun checkPrivacyPolicy() {
		
		// 既に表示中かもしれない
		if(dlgPrivacyPolicy?.get()?.isShowing == true) return
		
		val res_id = when(getString(R.string.language_code)) {
			"ja" -> R.raw.privacy_policy_ja
			"fr" -> R.raw.privacy_policy_fr
			else -> R.raw.privacy_policy_en
		}
		
		// プライバシーポリシーデータの読み込み
		val bytes = loadRawResource(res_id)
		if(bytes.isEmpty()) return
		
		// 同意ずみなら表示しない
		val digest = bytes.digestSHA256().encodeBase64Url()
		if(digest == Pref.spAgreedPrivacyPolicyDigest(pref)) return
		
		val dialog = AlertDialog.Builder(this)
			.setTitle(R.string.privacy_policy)
			.setMessage(bytes.decodeUTF8())
			.setNegativeButton(R.string.cancel) { _, _ ->
				finish()
			}
			.setOnCancelListener {
				finish()
			}
			.setPositiveButton(R.string.agree) { _, _ ->
				pref.edit().put(Pref.spAgreedPrivacyPolicyDigest, digest).apply()
			}
			.create()
		dlgPrivacyPolicy = WeakReference(dialog)
		dialog.show()
	}
	
}
