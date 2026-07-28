package fr.fortytwo.badgescanner

import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import fr.fortytwo.badgescanner.scan.ScanViewModel
import fr.fortytwo.badgescanner.ui.App

class MainActivity : ComponentActivity() {

    private val viewModel: ScanViewModel by viewModels()
    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        setContent {
            App(viewModel = viewModel, nfcAvailable = nfcAdapter != null)
        }
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableReaderMode(
            this,
            { tag -> viewModel.onTag(tag.id) },
            NfcAdapter.FLAG_READER_NFC_A
                or NfcAdapter.FLAG_READER_NFC_B
                or NfcAdapter.FLAG_READER_NFC_F
                or NfcAdapter.FLAG_READER_NFC_V
                or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }
}
