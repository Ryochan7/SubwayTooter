package jp.juggler.subwaytooter.table

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

import java.util.HashSet

import jp.juggler.subwaytooter.App1
import jp.juggler.util.LogCategory

object MutedApp : TableCompanion {
	
	private val log = LogCategory("MutedApp")
	
	internal const val table = "app_mute"
	const val COL_ID = "_id"
	const val COL_NAME = "name"
	private const val COL_TIME_SAVE = "time_save"
	
	override fun onDBCreate(db : SQLiteDatabase) {
		log.d("onDBCreate!")
		db.execSQL(
			"""create table if not exists $table
			($COL_ID INTEGER PRIMARY KEY
			,$COL_NAME text not null
			,$COL_TIME_SAVE integer not null
			)"""
		)
		db.execSQL(
			"create unique index if not exists ${table}_name on $table($COL_NAME)"
		)
	}
	
	override fun onDBUpgrade(db : SQLiteDatabase, oldVersion : Int, newVersion : Int) {
		if(oldVersion < 6 && newVersion >= 6) {
			onDBCreate(db)
		}
	}
	
	fun save(app_name : String?) {
		if(app_name == null) return
		try {
			val now = System.currentTimeMillis()
			
			val cv = ContentValues()
			cv.put(COL_NAME, app_name)
			cv.put(COL_TIME_SAVE, now)
			App1.database.replace(table, null, cv)
			
		} catch(ex : Throwable) {
			log.e(ex, "save failed.")
		}
		
	}
	
	fun createCursor() : Cursor {
		return App1.database.query(table, null, null, null, null, null, "$COL_NAME asc")
	}
	
	fun delete(name : String) {
		try {
			App1.database.delete(table, "$COL_NAME=?", arrayOf(name))
		} catch(ex : Throwable) {
			log.e(ex, "delete failed.")
		}
		
	}
	
	//	private static final String[] isMuted_projection = new String[]{COL_NAME};
	//	private static final String   isMuted_where = COL_NAME+"=?";
	//	private static final ThreadLocal<String[]> isMuted_where_arg = new ThreadLocal<String[]>() {
	//		@Override protected String[] initialValue() {
	//			return new String[1];
	//		}
	//	};
	//	public static boolean isMuted( String app_name ){
	//		if( app_name == null ) return false;
	//		try{
	//			String[] where_arg = isMuted_where_arg.get();
	//			where_arg[0] = app_name;
	//			Cursor cursor = App1.getDB().query( table, isMuted_projection,isMuted_where , where_arg, null, null, null );
	//			try{
	//				if( cursor.moveToFirst() ){
	//					return true;
	//				}
	//			}finally{
	//				cursor.close();
	//			}
	//		}catch( Throwable ex ){
	//			warning.e( ex, "load failed." );
	//		}
	//		return false;
	//	}
	
	val nameSet : HashSet<String>
		get() {
			val dst = HashSet<String>()
			try {
				App1.database.query(table, null, null, null, null, null, null)
					.use { cursor ->
						val idx_name = cursor.getColumnIndex(COL_NAME)
						while(cursor.moveToNext()) {
							val s = cursor.getString(idx_name)
							dst.add(s)
						}
						
					}
			} catch(ex : Throwable) {
				log.trace(ex)
			}
			
			return dst
		}
	
}
