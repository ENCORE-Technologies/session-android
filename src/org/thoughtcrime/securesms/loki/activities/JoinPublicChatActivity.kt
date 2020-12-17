package org.thoughtcrime.securesms.loki.activities

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.android.synthetic.main.activity_join_public_chat.*
import kotlinx.android.synthetic.main.fragment_enter_chat_url.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import nl.komponents.kovenant.ui.failUi
import nl.komponents.kovenant.ui.successUi
import org.thoughtcrime.securesms.BaseActionBarActivity
import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity
import org.thoughtcrime.securesms.loki.fragments.ScanQRCodeWrapperFragment
import org.thoughtcrime.securesms.loki.fragments.ScanQRCodeWrapperFragmentDelegate
import org.thoughtcrime.securesms.loki.protocol.shelved.SyncMessagesProtocol
import org.thoughtcrime.securesms.loki.utilities.OpenGroupUtilities
import java.lang.Exception

class JoinPublicChatActivity : PassphraseRequiredActionBarActivity(), ScanQRCodeWrapperFragmentDelegate {
    private val adapter = JoinPublicChatActivityAdapter(this)

    // region Lifecycle
    override fun onCreate(savedInstanceState: Bundle?, isReady: Boolean) {
        super.onCreate(savedInstanceState, isReady)
        // Set content view
        setContentView(R.layout.activity_join_public_chat)
        // Set title
        supportActionBar!!.title = resources.getString(R.string.activity_join_public_chat_title)
        // Set up view pager
        viewPager.adapter = adapter
        tabLayout.setupWithViewPager(viewPager)
    }
    // endregion

    // region Updating
    private fun showLoader() {
        loader.visibility = View.VISIBLE
        loader.animate().setDuration(150).alpha(1.0f).start()
    }

    private fun hideLoader() {
        loader.animate().setDuration(150).alpha(0.0f).setListener(object : AnimatorListenerAdapter() {

            override fun onAnimationEnd(animation: Animator?) {
                super.onAnimationEnd(animation)
                loader.visibility = View.GONE
            }
        })
    }
    // endregion

    // region Interaction
    override fun handleQRCodeScanned(url: String) {
        joinPublicChatIfPossible(url)
    }

    fun joinPublicChatIfPossible(url: String) {
        if (!Patterns.WEB_URL.matcher(url).matches() || !url.startsWith("https://")) {
            return Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show()
        }
        showLoader()
        val channel: Long = 1

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                OpenGroupUtilities.addGroup(this@JoinPublicChatActivity, url, channel)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hideLoader()
                    Toast.makeText(this@JoinPublicChatActivity, R.string.activity_join_public_chat_error, Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            SyncMessagesProtocol.syncAllOpenGroups(this@JoinPublicChatActivity)
            withContext(Dispatchers.Main) { finish() }
        }
    }
    // endregion
}

// region Adapter
private class JoinPublicChatActivityAdapter(val activity: JoinPublicChatActivity) : FragmentPagerAdapter(activity.supportFragmentManager) {

    override fun getCount(): Int {
        return 2
    }

    override fun getItem(index: Int): Fragment {
        return when (index) {
            0 -> EnterChatURLFragment()
            1 -> {
                val result = ScanQRCodeWrapperFragment()
                result.delegate = activity
                result.message = activity.resources.getString(R.string.activity_join_public_chat_scan_qr_code_explanation)
                result
            }
            else -> throw IllegalStateException()
        }
    }

    override fun getPageTitle(index: Int): CharSequence? {
        return when (index) {
            0 -> activity.resources.getString(R.string.activity_join_public_chat_enter_group_url_tab_title)
            1 -> activity.resources.getString(R.string.activity_join_public_chat_scan_qr_code_tab_title)
            else -> throw IllegalStateException()
        }
    }
}
// endregion

// region Enter Chat URL Fragment
class EnterChatURLFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_enter_chat_url, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        chatURLEditText.imeOptions = chatURLEditText.imeOptions or 16777216 // Always use incognito keyboard
        joinPublicChatButton.setOnClickListener { joinPublicChatIfPossible() }
    }

    private fun joinPublicChatIfPossible() {
        val inputMethodManager = requireContext().getSystemService(BaseActionBarActivity.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(chatURLEditText.windowToken, 0)
        var chatURL = chatURLEditText.text.trim().toString().toLowerCase().replace("http://", "https://")
        if (!chatURL.toLowerCase().startsWith("https")) {
            chatURL = "https://$chatURL"
        }
        (requireActivity() as JoinPublicChatActivity).joinPublicChatIfPossible(chatURL)
    }
}
// endregion