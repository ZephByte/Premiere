package dev.zephbyte.premiere.client.video

import com.mojang.blaze3d.vertex.PoseStack
import dev.zephbyte.premiere.Premiere
import dev.zephbyte.premiere.client.ClientScreens
import dev.zephbyte.premiere.client.PremiereClientConfig
import dev.zephbyte.premiere.geo.ScreenFacing
import dev.zephbyte.premiere.screen.ScreenDefinition
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.resources.Identifier
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.renderer.texture.OverlayTexture
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws each active screen as a single textured quad floating a hair in front
 * of the (vanilla, untouched) wall blocks. Video is fitted inside the wall
 * rectangle preserving aspect, centered, letterboxed by the wall itself.
 */
object ScreenRenderer {

    private const val FULL_BRIGHT = 0xF000F0
    private const val EPS = 0.005f
    private val LOADING_TEXTURE_ID = Premiere.id("loading/white")
    private val SPINNER_TEXTURE_ID = Premiere.id("loading/spinner")
    private var loadingTexture: DynamicTexture? = null
    private var spinnerTexture: DynamicTexture? = null

    // Snapshotted during extraction (which has level access); read during the
    // render pass, which must not touch the client level.
    @Volatile
    private var currentDimension: String? = null

    fun register() {
        LevelRenderEvents.END_EXTRACTION.register { context ->
            currentDimension = context.level().dimension().identifier().toString()
        }
        LevelRenderEvents.COLLECT_SUBMITS.register(::collect)
    }

    private fun collect(context: LevelRenderContext) {
        ClientScreens.drainRetired {
            it.destroyTexture()
        }

        val camera = context.levelState().cameraRenderState
        if (!camera.initialized) return
        val dimension = currentDimension ?: return

        for (active in ClientScreens.renderable()) {
            val player = active.player ?: continue
            val definition = active.definition
            if (definition.dimension != dimension) continue
            val textureId = player.textureForRender()
            val loading = player.isLoading
            if (textureId == null && !loading) continue

            // Fit the video inside the wall, preserving aspect.
            val videoAspect = player.aspect
            val wallAspect = definition.width.toFloat() / definition.height
            val quadWidth: Float
            val quadHeight: Float
            if (videoAspect >= wallAspect) {
                quadWidth = definition.width.toFloat()
                quadHeight = quadWidth / videoAspect
            } else {
                quadHeight = definition.height.toFloat()
                quadWidth = quadHeight * videoAspect
            }

            val poseStack = context.poseStack()
            poseStack.pushPose()
            val cameraPos = camera.pos
            poseStack.translate(
                definition.origin.x - cameraPos.x,
                definition.origin.y - cameraPos.y,
                definition.origin.z - cameraPos.z,
            )

            // Cinema letterbox: a black quad covering the whole wall, a hair
            // behind the picture, so unused screen area reads as screen
            // rather than as wall. (The video texture multiplied by black is
            // pure black — no extra pipeline needed.)
            val letterbox = textureId != null && PremiereClientConfig.letterboxBlack &&
                (quadWidth < definition.width || quadHeight < definition.height)
            if (letterbox) {
                submitQuad(
                    context, poseStack, textureId, definition,
                    definition.width.toFloat(), definition.height.toFloat(),
                    eps = EPS * 0.5f, red = 0, green = 0, blue = 0,
                )
            }
            if (textureId != null) {
                submitQuad(context, poseStack, textureId, definition, quadWidth, quadHeight, EPS, 255, 255, 255)
            }
            if (loading) submitLoading(context, poseStack, definition, hasVideoFrame = textureId != null)

            poseStack.popPose()
        }
    }

    /**
     * Covers the screen with a quiet black loading state and an animated ring.
     * When a previous frame exists (normal seek), it remains dimly visible;
     * initial startup gets a fully black screen.
     */
    private fun submitLoading(
        context: LevelRenderContext,
        poseStack: PoseStack,
        definition: ScreenDefinition,
        hasVideoFrame: Boolean,
    ) {
        val white = loadingTexture()
        submitQuad(
            context, poseStack, white, definition,
            definition.width.toFloat(), definition.height.toFloat(),
            eps = EPS * 1.5f,
            red = 0, green = 0, blue = 0,
            alpha = if (hasVideoFrame) 165 else 255,
        )

        val shortestSide = min(definition.width, definition.height).toFloat()
        val spinnerSize = (shortestSide * 0.22f).coerceIn(0.55f, 1.55f)
        submitSpinner(context, poseStack, spinnerTexture(), definition, spinnerSize)
    }

    /**
     * A single textured quad avoids the translucent depth/sorting artifacts
     * produced by building a world-space ring from many tiny quads.
     */
    private fun submitSpinner(
        context: LevelRenderContext,
        poseStack: PoseStack,
        textureId: Identifier,
        definition: ScreenDefinition,
        size: Float,
    ) {
        context.submitNodeCollector().submitCustomGeometry(
            poseStack,
            RenderTypes.entityTranslucentEmissive(textureId),
        ) { pose, consumer ->
            val plane = when (definition.facing) {
                ScreenFacing.SOUTH, ScreenFacing.EAST -> 1f + EPS * 2f
                ScreenFacing.NORTH, ScreenFacing.WEST -> -EPS * 2f
            }
            val onX = definition.facing.spansX
            val normalX = definition.facing.stepX.toFloat()
            val normalZ = definition.facing.stepZ.toFloat()
            val phase = (System.currentTimeMillis() % 800L) / 800.0 * PI * 2.0
            val phaseCos = cos(phase).toFloat()
            val phaseSin = sin(phase).toFloat()

            val centerSpan = definition.width / 2f
            val centerY = definition.height / 2f

            fun vertex(baseSpan: Float, baseY: Float, u: Float, v: Float) {
                val localSpan = baseSpan - centerSpan
                val localY = baseY - centerY
                val span = centerSpan + localSpan * phaseCos - localY * phaseSin
                val y = centerY + localSpan * phaseSin + localY * phaseCos
                val x = if (onX) span else plane
                val z = if (onX) plane else span
                consumer.addVertex(pose, x, y, z)
                    .setColor(255, 255, 255, 255)
                    .setUv(u, v)
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(FULL_BRIGHT)
                    .setNormal(pose, normalX, 0f, normalZ)
            }

            val spanLo = centerSpan - size / 2f
            val spanHi = centerSpan + size / 2f
            val yTop = centerY + size / 2f
            val yBottom = centerY - size / 2f
            val (left, right) = when (definition.facing) {
                ScreenFacing.SOUTH, ScreenFacing.WEST -> spanLo to spanHi
                ScreenFacing.NORTH, ScreenFacing.EAST -> spanHi to spanLo
            }
            vertex(left, yTop, 0f, 0f)
            vertex(left, yBottom, 0f, 1f)
            vertex(right, yBottom, 1f, 1f)
            vertex(right, yTop, 1f, 0f)
        }
    }

    /** A one-pixel white texture lets tint-only loading geometry stay asset-free. */
    private fun loadingTexture(): Identifier {
        if (loadingTexture == null) {
            val texture = DynamicTexture({ "Premiere loading texture" }, 1, 1, false)
            texture.pixels.setPixelABGR(0, 0, -1)
            texture.upload()
            Minecraft.getInstance().textureManager.register(LOADING_TEXTURE_ID, texture)
            loadingTexture = texture
        }
        return LOADING_TEXTURE_ID
    }

    /** Complete anti-aliased ring with a brightness gradient for rotation. */
    private fun spinnerTexture(): Identifier {
        if (spinnerTexture == null) {
            val texture = DynamicTexture({ "Premiere loading spinner" }, 64, 64, false)
            val pixels = texture.pixels
            val center = 31.5
            val radius = 20.5
            val halfThickness = 3.25
            for (y in 0 until 64) {
                for (x in 0 until 64) {
                    val dx = x - center
                    val dy = y - center
                    val distance = sqrt(dx * dx + dy * dy)
                    val coverage = (1.0 - abs(distance - radius) / halfThickness).coerceIn(0.0, 1.0)
                    if (coverage <= 0.0) {
                        pixels.setPixelABGR(x, y, 0)
                        continue
                    }
                    val angle = (atan2(dy, dx) + PI * 2.0) % (PI * 2.0)
                    // The full ring remains plainly visible; only its bright
                    // head moves, so it never reads as a broken arc.
                    val brightness = 0.58 + 0.42 * angle / (PI * 2.0)
                    val alpha = (255.0 * coverage * brightness).toInt().coerceIn(0, 255)
                    pixels.setPixelABGR(x, y, (alpha shl 24) or 0x00FFFFFF)
                }
            }
            texture.upload()
            Minecraft.getInstance().textureManager.register(SPINNER_TEXTURE_ID, texture)
            spinnerTexture = texture
        }
        return SPINNER_TEXTURE_ID
    }

    /**
     * One centered quad on the wall face, [eps] blocks out from it, tinted
     * [red]/[green]/[blue]. Local coords are relative to the wall origin;
     * "left" is the viewer's left looking at the face.
     */
    private fun submitQuad(
        context: LevelRenderContext,
        poseStack: PoseStack,
        textureId: Identifier,
        definition: ScreenDefinition,
        quadWidth: Float,
        quadHeight: Float,
        eps: Float,
        red: Int,
        green: Int,
        blue: Int,
        alpha: Int = 255,
        centerSpan: Float = definition.width / 2f,
        centerY: Float = definition.height / 2f,
    ) {
        context.submitNodeCollector().submitCustomGeometry(
            poseStack,
            RenderTypes.entityTranslucentEmissive(textureId),
        ) { pose, consumer ->
            val yTop = centerY + quadHeight / 2f
            val yBottom = centerY - quadHeight / 2f
            val spanLo = centerSpan - quadWidth / 2f
            val spanHi = centerSpan + quadWidth / 2f

            // (x, z) of the u=0 (viewer-left) and u=1 edges, plus the face plane.
            val (left, right, plane) = when (definition.facing) {
                ScreenFacing.SOUTH -> Triple(spanLo, spanHi, 1f + eps)
                ScreenFacing.NORTH -> Triple(spanHi, spanLo, -eps)
                ScreenFacing.EAST -> Triple(spanHi, spanLo, 1f + eps)
                ScreenFacing.WEST -> Triple(spanLo, spanHi, -eps)
            }
            val onX = definition.facing.spansX

            val normalX = definition.facing.stepX.toFloat()
            val normalZ = definition.facing.stepZ.toFloat()

            fun vertex(span: Float, y: Float, u: Float, v: Float) {
                val x = if (onX) span else plane
                val z = if (onX) plane else span
                consumer.addVertex(pose, x, y, z)
                    .setColor(red, green, blue, alpha)
                    .setUv(u, v)
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(FULL_BRIGHT)
                    .setNormal(pose, normalX, 0f, normalZ)
            }

            // Counter-clockwise as seen from the front.
            vertex(left, yTop, 0f, 0f)
            vertex(left, yBottom, 0f, 1f)
            vertex(right, yBottom, 1f, 1f)
            vertex(right, yTop, 1f, 0f)
        }
    }
}
