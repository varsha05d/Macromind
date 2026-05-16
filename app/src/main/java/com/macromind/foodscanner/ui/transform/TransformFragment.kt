package com.macromind.foodscanner.ui.transform

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.macromind.foodscanner.databinding.FragmentTransformBinding
import androidx.camera.lifecycle.ProcessCameraProvider
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class TransformFragment : Fragment() {

    private var _binding: FragmentTransformBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTransformBinding.inflate(inflater, container, false)
        val root: View = binding.root

        binding.buttonTest.setOnClickListener {
            testDependencies()
        }

        return root
    }

    private fun testDependencies() {
        try {
            // Test CameraX availability
            val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
            Log.d("TestUI", "CameraX initialized: ${cameraProviderFuture != null}")
            
            // Test ML Kit availability
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            Log.d("TestUI", "ML Kit initialized: ${recognizer != null}")
            


            binding.textStatus.text = "Status: Dependencies Loaded Successfully"
            Toast.makeText(context, "All dependencies are working!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            binding.textStatus.text = "Status: Error loading dependencies"
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("TestUI", "Error testing dependencies", e)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}