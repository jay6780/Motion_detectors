package com.m.motion_2;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.orhanobut.dialogplus.DialogPlus;
import com.orhanobut.dialogplus.OnClickListener;
import com.orhanobut.dialogplus.OnDismissListener;
import com.orhanobut.dialogplus.ViewHolder;

import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.JavaCameraView;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CountAct extends AppCompatActivity implements CameraBridgeViewBase.CvCameraViewListener2 {
    private Mat prevFrame;
    private boolean dialogShown = false; // Add this flag
//    TextView textView;
    private AlertDialog shapeAlertDialog;
    private AlertDialog fishDetectedAlertDialog;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    private float mScaleFactor = 1.0f;
    private JavaCameraView cameraView;
    private ImageButton back,refresh;
    private int fishCount = 0;
    private boolean isFishDetected = false;
    static {
        if (!OpenCVLoader.initDebug()) {
            // Handle OpenCV initialization error
            Log.e("CountAct", "OpenCV initialization error");
        }
    }

    private static final double minFishSize = 100.0;  // Adjust this value based on your needs
    private static final double maxFishSize = 1000.0;
    private Handler handler = new Handler();
    private boolean dialogScheduled = false;
    private boolean conditionToStopScheduledDialog = false;
    private int zoomLevel = 0;
    private CameraBridgeViewBase.CvCameraViewListener2 cameraListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_count);
//        textView = findViewById(R.id.fishCount);
        back = findViewById(R.id.back);
        refresh = findViewById(R.id.refresh);
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, PackageManager.PERMISSION_GRANTED);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }
        cameraView = findViewById(R.id.object);
        cameraView.setVisibility(View.VISIBLE);
        cameraView.setCvCameraViewListener(this);


        ImageButton zoomInButton = findViewById(R.id.zoomInButton);
        ImageButton zoomOutButton = findViewById(R.id.zoomOutButton);

        zoomInButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                zoomIn();
            }
        });

        zoomOutButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                zoomOut();
            }
        });

        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(getApplicationContext(),front_page.class);
                startActivity(i);
                overridePendingTransition(0,0);
                finish();
            }
        });

        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Create a ProgressDialog
                ProgressDialog progressDialog = new ProgressDialog(CountAct.this);
                progressDialog.setMessage("Refreshing...");
                progressDialog.setCancelable(false);
                progressDialog.show();

                // Delay for 2 seconds using a Handler
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        // Dismiss the ProgressDialog
                        progressDialog.dismiss();

                        // Start the new activity
                        Intent i = new Intent(getApplicationContext(), CountAct.class);
                        startActivity(i);
                        overridePendingTransition(0, 0);
                        finish();
                    }
                }, 2000); // 2000 milliseconds = 2 seconds
            }
        });

        requestCameraPermission();
    }

    private void zoomIn() {
        if (cameraView != null) {
            zoomLevel += 1;
            cameraView.setZoom(zoomLevel);
        }
    }

    private void zoomOut() {
        if (cameraView != null && zoomLevel > 0) {
            zoomLevel -= 1;
            cameraView.setZoom(zoomLevel);
        }
    }

    private void requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        } else {
            initializeCamera();
        }
    }

    private void initializeCamera() {
        if (cameraView != null) {
            cameraView.setCameraIndex(JavaCameraView.CAMERA_ID_BACK);
            cameraView.setMaxFrameSize(1280, 720);
            cameraView.setZoom(zoomLevel); // Set the initial zoom level
            cameraView.enableView();
        }
    }



    private void countFish(Mat image) {
        // Convert the image to grayscale for motion detection
        Mat grayImage = new Mat();
        Imgproc.cvtColor(image, grayImage, Imgproc.COLOR_BGR2GRAY);

        if (prevFrame == null) {
            // Initialize the previous frame if it's the first frame
            prevFrame = grayImage.clone();
        }

        // Calculate the absolute difference between the current frame and the previous frame
        Mat frameDiff = new Mat();
        Core.absdiff(prevFrame, grayImage, frameDiff);

        // Threshold the difference image to create a binary mask
        Mat mask = new Mat();
        Imgproc.threshold(frameDiff, mask, 30, 255, Imgproc.THRESH_BINARY);

        // Find contours in the binary mask
        List<MatOfPoint> contours = new ArrayList<>();
        Imgproc.findContours(mask, contours, new Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        // Iterate through the contours and filter out small and non-fish shapes
        for (MatOfPoint contour : contours) {
            double area = Imgproc.contourArea(contour);
            if (area > minFishSize && area < maxFishSize) {
                // Calculate the average color of the fish
                Scalar color = calculateAverageColor(image, contour, true);

                // Draw the contour on the original image using the color
                Imgproc.drawContours(image, contours, contours.indexOf(contour), color, 2);
                fishCount++;
            }
        }

        // Update the previous frame with the current frame
        grayImage.copyTo(prevFrame);
    }


    private Scalar calculateAverageColor(Mat image, MatOfPoint contour, boolean countFish) {
        if (countFish) {
            Mat mask = Mat.zeros(image.size(), CvType.CV_8U);
            List<MatOfPoint> contours = new ArrayList<>();
            contours.add(contour);
            Imgproc.drawContours(mask, contours, 0, new Scalar(255), -1); // Fill the contour with white

            Scalar meanColor = Core.mean(image, mask);

            double area = Imgproc.contourArea(contour);
            if (area > minFishSize && area < maxFishSize) {
                fishCount++;
            }

            return meanColor;
        } else {
            // Return black color when countFish is false
            return new Scalar(0, 0, 0);
        }
    }



    @Override
    public void onCameraViewStarted(int width, int height) {
        // Initialize any image processing parameters here if needed
    }

    @Override
    public void onCameraViewStopped() {
        // Release any resources used for image processing here
    }

    @Override
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        final Mat rgba = inputFrame.rgba();

        // Reset the fish count for each frame
        fishCount = 0;

        // Perform real-time image processing to detect fish
        countFish(rgba);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
//                if (textView != null) {
//                    textView.setText("Fish Count: " + fishCount);
//                }

                // Schedule the AlertDialog with the fish count if it hasn't been scheduled and not already shown
                if (!dialogScheduled && !dialogShown) {
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            // Check if the activity is still running before showing the dialog
                            if (!isFinishing() && !isDestroyed()) {
                                showFishCountDialog(fishCount);
                            }
                            dialogScheduled = false; // Set the flag to false so it can be scheduled again
                            dialogShown = true; // Set the flag to true after showing the dialog
                        }
                    }, 10000); // 6500 milliseconds = 6.5 seconds (to ensure a delay after fishCount is updated)
                    dialogScheduled = true; // Set the flag to true to prevent repeated scheduling
                }
            }
        });

        return rgba;
    }
    private void showFishCountDialog(final int fishCount) {
        View contentView = getLayoutInflater().inflate(R.layout.custom_fish_count_dialog, null);
        TextView fishCountTextView = contentView.findViewById(R.id.fishCountTextView);
        EditText tankNameInput = contentView.findViewById(R.id.tankNameInput);
        fishCountTextView.setText(String.valueOf(fishCount));
        DialogPlus dialog = DialogPlus.newDialog(this)
                .setContentHolder(new ViewHolder(contentView))
                .setHeader(R.layout.dialog_header)
                .setFooter(R.layout.dialog_footer)
                .setGravity(Gravity.CENTER)
                .setCancelable(false)
                .setExpanded(false)
                .setContentWidth(ViewGroup.LayoutParams.WRAP_CONTENT)
                .setContentHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
                .setOnDismissListener(new OnDismissListener() {
                    @Override
                    public void onDismiss(DialogPlus dialog) {
                        // Add any code to handle dialog dismissal
                    }
                })
                .setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(DialogPlus dialog, View view) {
                        if (view.getId() == R.id.saveButton) {
                            String tankName = tankNameInput.getText().toString().trim();
                            if (!tankName.isEmpty()) {
                                // Get the current date and time
                                SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault());
                                String currentDateTime = dateFormat.format(new Date());

                                // Create a Firebase reference to the "tank" node
                                FirebaseDatabase database = FirebaseDatabase.getInstance();
                                DatabaseReference tankRef = database.getReference("tank");

                                // Set the fish count, tank name, and timestamp as child values in a single node
                                DatabaseReference newFishCountRef = tankRef.push();
                                newFishCountRef.child("tankName").setValue(tankName);
                                newFishCountRef.child("fishCount").setValue(fishCount);
                                newFishCountRef.child("timeStamp").setValue(currentDateTime);

                                // Show a success toast message
                                Toast.makeText(getApplicationContext(), "Fish count saved successfully in tank name: " + tankName, Toast.LENGTH_SHORT).show();

                                dialog.dismiss(); // Close the dialog after saving
                            } else {
                                Toast.makeText(getApplicationContext(), "Tank name is required", Toast.LENGTH_SHORT).show();
                            }
                        } else if (view.getId() == R.id.tryAgainButton) {
                            // Add your "Try Again" logic here
                            Intent i = new Intent(getApplicationContext(), CountAct.class);
                            startActivity(i);
                            overridePendingTransition(0, 0);
                            finish();
                        } else if (view.getId() == R.id.cancelButton) {
                            dialog.dismiss(); // Close the dialog if the "Cancel" button is clicked
                        }
                    }
                })
                .create();

        dialog.show();
    }



    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, initialize the camera
                initializeCamera();
            } else {
                Toast.makeText(this, "Camera permission is required to use this app.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (cameraView != null) {
            cameraView.enableView();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (cameraView != null) {
            cameraView.disableView();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraView != null) {
            cameraView.disableView();
        }
    }
    public void onBackPressed(){
        Intent i = new Intent(getApplicationContext(),front_page.class);
        startActivity(i);
        overridePendingTransition(0,0);
        finish();
    }
}
