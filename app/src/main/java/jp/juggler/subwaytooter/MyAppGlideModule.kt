package jp.juggler.subwaytooter

import android.content.Context
import android.graphics.drawable.PictureDrawable
import androidx.annotation.NonNull
import androidx.annotation.Nullable

import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.resource.SimpleResource
import com.bumptech.glide.load.resource.transcode.ResourceTranscoder
import com.bumptech.glide.module.AppGlideModule
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import java.io.IOException
import java.io.InputStream

@GlideModule
class MyAppGlideModule : AppGlideModule() {
	
	// Decodes an SVG internal representation from an [InputStream].
	inner class SvgDecoder : ResourceDecoder<InputStream, SVG> {
		
		override fun handles(source : InputStream, options : Options) : Boolean {
			// TODO: Can we tell?
			return true
		}
		
		@Throws(IOException::class)
		override fun decode(
			source : InputStream, width : Int, height : Int, options : Options
		) : Resource<SVG>? {
			try {
				val svg = SVG.getFromInputStream(source)
				return SimpleResource(svg)
			} catch(ex : SVGParseException) {
				throw IOException("Cannot load SVG from stream", ex)
			}
		}
	}
	
	// Convert the [SVG]'s internal representation to an Android-compatible one ([Picture]).
	inner class SvgDrawableTranscoder : ResourceTranscoder<SVG, PictureDrawable> {
		
		@Nullable
		override fun transcode(
			toTranscode : Resource<SVG>, options : Options
		) : Resource<PictureDrawable> {
			val svg = toTranscode.get()
			val picture = svg.renderToPicture()
			val drawable = PictureDrawable(picture)
			return SimpleResource(drawable)
		}
	}
	
	// v3との互換性のためにAndroidManifestを読むかどうか(デフォルトtrue)
	override fun isManifestParsingEnabled() : Boolean {
		return false
	}
	
	override fun registerComponents(context : Context, glide : Glide, registry : Registry) {
		// デフォルト実装は何もしないらしい
		super.registerComponents(context, glide, registry)
		
		// App1を初期化してからOkHttp3Factoryと連動させる
		App1.prepare(context.applicationContext)
		App1.registerGlideComponents(context, glide, registry)
		
		//SVGデコーダーの追加
		registry
			.register(SVG::class.java, PictureDrawable::class.java, SvgDrawableTranscoder())
			.append(InputStream::class.java, SVG::class.java, SvgDecoder())
	}
	
	override fun applyOptions(context : Context, builder : GlideBuilder) {
		// デフォルト実装は何もしないらしい
		super.applyOptions(context, builder)
		
		// App1を初期化してから色々する
		App1.prepare(context.applicationContext)
		App1.applyGlideOptions(context, builder)
	}
	
}
