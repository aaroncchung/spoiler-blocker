package io.github.aaroncchung.spoilerblocker.status

import org.junit.Assert.assertEquals
import org.junit.Test

class PostNotificationsPermissionTest {

    /** A blocker is on and the app may not post: the case in which something has to happen. */
    private fun step(
        blockerOn: Boolean = true,
        canPost: Boolean = false,
        statusChannelBlocked: Boolean = false,
        refusedBefore: Boolean = false,
        request: PostPermissionRequest = PostPermissionRequest.NOT_ASKED,
    ) = postPermissionStep(blockerOn, canPost, statusChannelBlocked, refusedBefore, request)

    @Test
    fun `the owner is asked when a blocker is on and nothing was asked yet`() {
        assertEquals(PostPermissionStep.ASK, step())
    }

    @Test
    fun `nothing happens while no blocker is on`() {
        assertEquals(PostPermissionStep.NOTHING, step(blockerOn = false))
        // Not even after a refusal: there is no notification to miss.
        assertEquals(PostPermissionStep.NOTHING, step(blockerOn = false, refusedBefore = true))
        assertEquals(
            PostPermissionStep.NOTHING,
            step(blockerOn = false, request = PostPermissionRequest.ANSWERED),
        )
        assertEquals(PostPermissionStep.NOTHING, step(blockerOn = false, canPost = true, statusChannelBlocked = true))
    }

    @Test
    fun `nothing happens when the app may post`() {
        for (request in PostPermissionRequest.entries) {
            assertEquals(PostPermissionStep.NOTHING, step(canPost = true, request = request))
        }
    }

    @Test
    fun `the card stays away while the dialog is showing`() {
        assertEquals(PostPermissionStep.NOTHING, step(request = PostPermissionRequest.ASKING))
    }

    @Test
    fun `after a refusal the card shows and the owner is not asked again`() {
        // Refused in the dialog a moment ago.
        assertEquals(
            PostPermissionStep.SHOW_CARD,
            step(refusedBefore = true, request = PostPermissionRequest.ANSWERED),
        )
        // Refused earlier, and the app was opened again since.
        assertEquals(PostPermissionStep.SHOW_CARD, step(refusedBefore = true))
    }

    @Test
    fun `a dialog closed without an answer shows the card, and the dialog again next time`() {
        // Android does not count this as a refusal, so refusedBefore stays false.
        assertEquals(
            PostPermissionStep.SHOW_CARD,
            step(refusedBefore = false, request = PostPermissionRequest.ANSWERED),
        )
        // The next time the blocker list is opened, the request starts again
        // from NOT_ASKED, and so the owner is asked again.
        assertEquals(
            PostPermissionStep.ASK,
            step(refusedBefore = false, request = PostPermissionRequest.NOT_ASKED),
        )
    }

    @Test
    fun `when only the status channel is off the card shows and the owner is not asked`() {
        // Android's dialog is about the app as a whole, which may post.
        assertEquals(PostPermissionStep.SHOW_CARD, step(canPost = true, statusChannelBlocked = true))
        assertEquals(
            PostPermissionStep.SHOW_CARD,
            step(canPost = true, statusChannelBlocked = true, request = PostPermissionRequest.ANSWERED),
        )
    }

    @Test
    fun `when the app may not post and the channel is off too, the owner is asked first`() {
        assertEquals(PostPermissionStep.ASK, step(statusChannelBlocked = true))
        // Allowed in the dialog: the channel is still off.
        assertEquals(
            PostPermissionStep.SHOW_CARD,
            step(canPost = true, statusChannelBlocked = true, request = PostPermissionRequest.ANSWERED),
        )
    }
}
