package com.adrianjg.dlab.image;

import org.apache.commons.math3.stat.regression.SimpleRegression;

import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Vector;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import android.R;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.qualcomm.vuforia.CameraCalibration;
import com.qualcomm.vuforia.CameraDevice;
import com.qualcomm.vuforia.Frame;
import com.qualcomm.vuforia.Image;
import com.qualcomm.vuforia.Matrix34F;
import com.qualcomm.vuforia.Matrix44F;
import com.qualcomm.vuforia.Rectangle;
import com.qualcomm.vuforia.Renderer;
import com.qualcomm.vuforia.State;
import com.qualcomm.vuforia.Tool;
import com.qualcomm.vuforia.Trackable;
import com.qualcomm.vuforia.TrackableResult;
import com.qualcomm.vuforia.VIDEO_BACKGROUND_REFLECTION;
import com.qualcomm.vuforia.Vec3F;
import com.qualcomm.vuforia.VideoBackgroundConfig;
import com.qualcomm.vuforia.Vuforia;
import com.adrianjg.dlab.ApplicationSession;
import com.adrianjg.dlab.utils.CubeShaders;
import com.adrianjg.dlab.utils.FivePointDerivative;
import com.adrianjg.dlab.utils.LoadingDialogHandler;
import com.adrianjg.dlab.utils.Application3DModel;
import com.adrianjg.dlab.utils.SampleUtils;
import com.adrianjg.dlab.utils.Heart;
import com.adrianjg.dlab.utils.Texture;
import com.adrianjg.dlab.utils.LineShaders;

public class ImageTargetRenderer implements GLSurfaceView.Renderer {

	private static final String LOGTAG = "ImageTargetRenderer";

	private ApplicationSession vuforiaAppSession;
	private ImageTargets mActivity;
	private Vector<Texture> mTextures;

	private int shaderProgramID;
	private int vertexHandle;
	private int normalHandle;
	private int textureCoordHandle;
	private int mvpMatrixHandle;
	private int texSampler2DHandle;

	private Heart mHeart;
	private float kBuildingScale = 12.0f;
	private Application3DModel mBuildingsModel;
	private Renderer mRenderer;
	boolean mIsActive = false;

	private static float OBJECT_SCALE_FLOAT = 55.0f;
	float max = OBJECT_SCALE_FLOAT;
	float min = (float) (OBJECT_SCALE_FLOAT * 0.75);

	private float period = 40;
	private int counterX = 0;
	private int counterY = 0;
	private int counterZ = 0;

	// Signal processing variables
	Handler activityHandler = new Handler(Looper.getMainLooper());
	CameraCalibration cameraCalib;
	private final int RGB565_FORMAT = 1;
	private Bitmap cameraBitmap;
	private Vec3F[][] pixelLevelLocations;
	private double[] colorSpectrum;
	private Vec3F measureLoc;
	private double colorCount;
	private int[][] colorPixels;
	private int colorLevelsMeasured;
	
	//Plotter
	public native double[] fft(int[] values);
	private Paint paint = new Paint();	
	private Canvas canvas;
	private int[] values = new int[640];
	
    private static String[] verlabels;
    private List<Integer> sampleValues = new ArrayList<Integer>();
    private FivePointDerivative deriv=new FivePointDerivative(20);
    
    private float width = 10.0f;
    private float graphheight;
    private float border;

    int bufferSize;
    private int power;
    private boolean found;

    static float minY = 0;
    static float maxY;

    static float range = (int) Math.pow(2, 10);
    private int lastX;
    float startX;

    private double incrementX;
    private double slowFactor;
    private int channel;

	private float location = -10000f;
	private float interval = 0f;
	// Open GL magic
	private int vbShaderProgramID = 0;
	private int vbVertexHandle = 0;
	private int lineOpacityHandle = 0;
	private int lineColorHandle = 0;
	private int mvpMatrixButtonsHandle = 0;

	private Rectangle[] renderRectangle;
	private LinkedList<Double> last10Obs;

	public ImageTargetRenderer(ImageTargets activity, ApplicationSession session) {
		mActivity = activity;
		vuforiaAppSession = session;
	}

	private void initRendering() {
		
		mHeart = new Heart();
		mRenderer = Renderer.getInstance();
		found = false;
		
		cameraCalib = CameraDevice.getInstance().getCameraCalibration();
		float[] resolution = cameraCalib.getSize().getData();
		cameraBitmap = Bitmap.createBitmap((int) resolution[0],
				(int) resolution[1], Bitmap.Config.RGB_565); //Use ARGB_8888 for IR
		
		colorSpectrum = new double[] { 4, 6, 8, 10, 12, 14 };
		pixelLevelLocations = new Vec3F[6][];
		
		float x = -0.63f;
		float[] y = {-2.3f,-1.38f, -0.46f, 0.46f, 1.38f, 2.3f};
		
		pixelLevelLocations[0] = getColorLevelArea(x, 0.46f, 0);
		pixelLevelLocations[1] = getColorLevelArea(x, 0.46f, 0);
		pixelLevelLocations[2] = getColorLevelArea(x, 0.46f, 0);
		pixelLevelLocations[3] = getColorLevelArea(x, 0.46f, 0);
		pixelLevelLocations[4] = getColorLevelArea(x, 0.46f, 0);
		pixelLevelLocations[5] = getColorLevelArea(x, 0.46f, 0);

		renderRectangle = new Rectangle[2];
		renderRectangle[0] = new Rectangle(-0.2f, 1.0f, 0.5f, 0.3f);
		renderRectangle[1] = new Rectangle(-3.625f, 3.625f, 3.625f, -3.625f);

		GLES20.glClearColor(0.0f, 0.0f, 0.0f, Vuforia.requiresAlpha() ? 0.0f
				: 1.0f);

		for (Texture t : mTextures) {
			GLES20.glGenTextures(1, t.mTextureID, 0);
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t.mTextureID[0]);
			GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
					GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
			GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
					GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
			GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
					t.mWidth, t.mHeight, 0, GLES20.GL_RGBA,
					GLES20.GL_UNSIGNED_BYTE, t.mData);
		}

		shaderProgramID = SampleUtils.createProgramFromShaderSrc(
				CubeShaders.CUBE_MESH_VERTEX_SHADER,
				CubeShaders.CUBE_MESH_FRAGMENT_SHADER);

		vertexHandle = GLES20.glGetAttribLocation(shaderProgramID,
				"vertexPosition");
		normalHandle = GLES20.glGetAttribLocation(shaderProgramID,
				"vertexNormal");
		textureCoordHandle = GLES20.glGetAttribLocation(shaderProgramID,
				"vertexTexCoord");
		mvpMatrixHandle = GLES20.glGetUniformLocation(shaderProgramID,
				"modelViewProjectionMatrix");
		texSampler2DHandle = GLES20.glGetUniformLocation(shaderProgramID,
				"texSampler2D");

		vbShaderProgramID = SampleUtils.createProgramFromShaderSrc(
				LineShaders.LINE_VERTEX_SHADER,
				LineShaders.LINE_FRAGMENT_SHADER);
		vbVertexHandle = GLES20.glGetAttribLocation(vbShaderProgramID,
				"vertexPosition");
		mvpMatrixButtonsHandle = GLES20.glGetUniformLocation(vbShaderProgramID,
				"modelViewProjectionMatrix");
		lineOpacityHandle = GLES20.glGetUniformLocation(vbShaderProgramID,
				"opacity");
		lineColorHandle = GLES20.glGetUniformLocation(vbShaderProgramID,
				"color");

		mActivity.loadingDialogHandler
				.sendEmptyMessage(LoadingDialogHandler.HIDE_LOADING_DIALOG);
	}

	private void renderFrame() {
		GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

		State state = mRenderer.begin();
		mRenderer.drawVideoBackground();

		GLES20.glEnable(GLES20.GL_DEPTH_TEST);
		GLES20.glEnable(GLES20.GL_CULL_FACE);
		GLES20.glCullFace(GLES20.GL_BACK);

		if (Renderer.getInstance().getVideoBackgroundConfig().getReflection() == VIDEO_BACKGROUND_REFLECTION.VIDEO_BACKGROUND_REFLECTION_ON)
			GLES20.glFrontFace(GLES20.GL_CW); // Front camera
		else
			GLES20.glFrontFace(GLES20.GL_CCW); // Back camera

		for (int tIdx = 0; tIdx < state.getNumTrackableResults(); tIdx++) {
			
			Arrays.fill(values, 0);
			TrackableResult result = state.getTrackableResult(tIdx);
			Trackable trackable = result.getTrackable();

			printUserData(trackable);
			Matrix44F modelViewMatrix_Vuforia = Tool
					.convertPose2GLMatrix(result.getPose());

			float[] modelViewMatrix = modelViewMatrix_Vuforia.getData();
			float[] modelViewMatrixSignal = modelViewMatrix_Vuforia.getData();

			float[] vbVertices = initGLVertices();

			int textureIndex = 0;

			float[] modelViewProjection = new float[16];
			float[] modelViewProjectionSignal = new float[16];

			/**
			 * Heart rendering
			 */
			Matrix.translateM(modelViewMatrix, 0, 0.0f, 0.0f,
					OBJECT_SCALE_FLOAT);
			Matrix.scaleM(modelViewMatrix, 0, 55, 55, 55);
			Matrix.multiplyMM(modelViewProjection, 0, vuforiaAppSession
					.getProjectionMatrix().getData(), 0, modelViewMatrix, 0);

			GLES20.glUseProgram(shaderProgramID);
			GLES20.glVertexAttribPointer(vertexHandle, 3, GLES20.GL_FLOAT,
					false, 0, mHeart.getVertices());
			GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT,
					false, 0, mHeart.getNormals());
			GLES20.glVertexAttribPointer(textureCoordHandle, 2,
					GLES20.GL_FLOAT, false, 0, mHeart.getTexCoords());
 
			GLES20.glEnableVertexAttribArray(vertexHandle);
			GLES20.glEnableVertexAttribArray(normalHandle);
			GLES20.glEnableVertexAttribArray(textureCoordHandle);
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,
					mTextures.get(textureIndex).mTextureID[0]);
			GLES20.glUniform1i(texSampler2DHandle, 0);
			GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false,
					modelViewProjection, 0);

			GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 672);
			GLES20.glDisableVertexAttribArray(vertexHandle);
			GLES20.glDisableVertexAttribArray(normalHandle);
			GLES20.glDisableVertexAttribArray(textureCoordHandle);
			SampleUtils.checkGLError("Render Frame");

			/**
			 * Signal stuff rendering
			 */
			Matrix.scaleM(modelViewMatrixSignal, 0, 35, 35, 35);
			Matrix.multiplyMM(modelViewProjectionSignal, 0, vuforiaAppSession
					.getProjectionMatrix().getData(), 0, modelViewMatrixSignal,
					0);

			// Render frame around button
			GLES20.glUseProgram(vbShaderProgramID);
			GLES20.glVertexAttribPointer(vbVertexHandle, 3, GLES20.GL_FLOAT,
					false, 0, fillBuffer(vbVertices));

			GLES20.glEnableVertexAttribArray(vbVertexHandle);
			GLES20.glUniform1f(lineOpacityHandle, 1.0f);
			GLES20.glUniform3f(lineColorHandle, 0.0f, 1.0f, 0.0f);

			GLES20.glUniformMatrix4fv(mvpMatrixButtonsHandle, 1, false,
					modelViewProjectionSignal, 0);
			GLES20.glDrawArrays(GLES20.GL_LINES, 0, 8 * renderRectangle.length);
			GLES20.glDisableVertexAttribArray(vbVertexHandle);

			final float xMeasure = modelViewMatrixSignal[0];
			final float yMeasure = modelViewMatrixSignal[3]-275;
			int measuredPixel = 0;

			measureLoc = new Vec3F(4.9f, 4.9f, 0);
			last10Obs = new LinkedList<Double>();

			
			cameraBitmap = getCameraBitmap(state);
			Matrix34F pose = state.getTrackableResult(0).getPose();

			int[][] pixels = new int[colorSpectrum.length][];
			for (int i = 0; i < colorSpectrum.length; i++) {
				int[] ps = getPixelsOnBitmap(pixelLevelLocations[i], pose);
				pixels[i] = averagePixels(ps);
			}
			
			int[] reds = new int[colorSpectrum.length];

			for (int i = 0; i < colorSpectrum.length; i++) {
				reds[i] = pixels[i][0];
			}

			measuredPixel = getPixelsOnBitmap(new Vec3F[] { measureLoc },
					pose)[0];

			double measured = byteSamplingModel(reds, Color.red(measuredPixel));
			colorLevelsMeasured = measuredPixel;
			colorPixels = pixels;

			last10Obs.add(measured);
			if (last10Obs.size() > 10) {
				last10Obs.removeFirst();
			}

			double sum = 0;
			for (double m : last10Obs) {
				sum += m;
			}
			colorCount = sum / 10;
			colorCount = 0;
			final String message;
			
			int observation = mActivity.getCurrentColor();
			int measPosition = 0;
			
			int r = (observation)&0xFF;
			int g = (observation>>8)&0xFF;
			int b = (observation>>16)&0xFF;
			
			if(r<=50 && g<= 50 && b <= 150 && b >= 120) {
				found = true;
				setLocation(xMeasure);
				measPosition = scaleNumber((int)location, (int)modelViewMatrix[0], 160, 1000, 0, 800);
				message = "Found! Position at:" + String.valueOf(measPosition);
			} else {
				found = false;
				
				interval = interval + 11;
				if(interval>= 1160) {
					interval = 0;
				}
				
				measPosition = 0;
				resetLocation();
				message = "Searching...";
			}
			
			final int measurementOnDisplay = measuredPixel;

			activityHandler.post(new Runnable() {
				public void run() {
					mActivity.updateByteCount(message, measurementOnDisplay, found);
				}
			});

			SampleUtils.checkGLError("FrameMarkers render frame");
		}
		GLES20.glDisable(GLES20.GL_DEPTH_TEST);
		mRenderer.end();
	}
	
	/**
	 * Color sampling utils
	 */
	private Vec3F[] getColorLevelArea(float cx, float cy, float cz) {
		Vec3F[] samples = new Vec3F[9];
		int r = 3, c = 3;
		for (int i = 0; i < r; i++) {
			for (int j = 0; j < c; j++) {
				samples[i * r + j] = new Vec3F(cx + (i - 1) * 0.1f, cy
						+ (j - 1) * 0.1f, cz);
			}
		}
		return samples;
	}

	public double getColorCount() {
		return colorCount;
	}

	public String[] getColorPixels() {
		String[] result = new String[colorSpectrum.length];
		for (int i = 0; i < result.length; i++) {
			result[i] = String.format("%d#%d#%d#%d", (int) colorSpectrum[i],
					colorPixels[i][0], colorPixels[i][1], colorPixels[i][2]);
		}
		return result;
	}

	public String getColorLevelsMeasured() {
		return String.format("%.3f#%d#%d#%d", colorCount,
				Color.red(colorLevelsMeasured), Color.green(colorLevelsMeasured),
				Color.blue(colorLevelsMeasured));
	}

	private int[] averagePixels(int[] pixels) {
		int redSum = 0;
		int blueSum = 0;
		int greenSum = 0;
		for (int i = 0; i < pixels.length; i++) {
			redSum += Color.red(pixels[i]);
			blueSum += Color.blue(pixels[i]);
			greenSum += Color.green(pixels[i]);
		}
		return new int[] { redSum / pixels.length, greenSum / pixels.length,
				blueSum / pixels.length };

	}

	private Buffer fillBuffer(float[] array) {
		ByteBuffer bb = ByteBuffer.allocateDirect(4 * array.length);
		bb.order(ByteOrder.LITTLE_ENDIAN);
		for (float d : array)
			bb.putFloat(d);
		bb.rewind();
		return bb;
	}

	private double byteSamplingModel(int[] pixels, int measurement) {
		SimpleRegression model = new SimpleRegression(true);
		double[][] data = new double[colorSpectrum.length][2];
		for (int i = 0; i < colorSpectrum.length; i++) {
			data[i][0] = (double) pixels[i];
			data[i][1] = (double) colorSpectrum[i];
		}
		model.addData(data);
		return model.predict(measurement);
	}

	private int[] getPixelsOnBitmap(Vec3F[] vectors, Matrix34F pose) {

		int[] pixels = new int[vectors.length];

		for (int i = 0; i < vectors.length; i++) {
			float[] point = Tool.projectPoint(cameraCalib, pose, vectors[i])
					.getData();
			pixels[i] = cameraBitmap.getPixel(Math.round(point[0]),
					Math.round(point[1]));
		}

		return pixels;
	}
	
	/**
	 * Scale a number @param meas found in the range @param lowerBound, @param maxBound
	 * to an interval @param a, @param b. Requires to know @param framePosition.
	 * @return scaled number.
	 */
	private int scaleNumber(int meas, int framePosition, int lowerBound, int maxBound, int a, int b) {
		int x = framePosition - lowerBound;
		int y = framePosition + maxBound;
		
		double scale = (double)(b - a) / (double)(y - x);
		return (int)(a + ((meas - x)*scale));
	}

	private Bitmap getCameraBitmap(State state) {

		Image image = null;
		Frame frame = state.getFrame();
		for (int i = 0; i < frame.getNumImages(); i++) {
			image = frame.getImage(i);
			if (image.getFormat() == RGB565_FORMAT) {
				break;
			}
		}

		if (image != null) {
			ByteBuffer buffer = image.getPixels();
			cameraBitmap.copyPixelsFromBuffer(buffer);
			return cameraBitmap;

		} else {
			Log.e(LOGTAG, "image not found.");
		}
		return null;
	}

	private void printUserData(Trackable trackable) {
		String userData = (String) trackable.getUserData();
		Log.d(LOGTAG, "UserData:Retreived User Data	\"" + userData + "\"");
	}

	public void setTextures(Vector<Texture> textures) {
		mTextures = textures;
	}

	private float[] initGLVertices() {
		float[] vertices = new float[renderRectangle.length * 24];
		int vInd = 0;

		for (Rectangle rect : renderRectangle) {
			vertices[vInd] = rect.getLeftTopX();
			vertices[vInd + 1] = rect.getLeftTopY();
			vertices[vInd + 2] = 0.0f;
			vertices[vInd + 3] = rect.getRightBottomX();

			vertices[vInd + 4] = rect.getLeftTopY();
			vertices[vInd + 5] = 0.0f;
			vertices[vInd + 6] = rect.getRightBottomX();
			vertices[vInd + 7] = rect.getLeftTopY();
			vertices[vInd + 8] = 0.0f;
			vertices[vInd + 9] = rect.getRightBottomX();
			vertices[vInd + 10] = rect.getRightBottomY();
			vertices[vInd + 11] = 0.0f;
			vertices[vInd + 12] = rect.getRightBottomX();
			vertices[vInd + 13] = rect.getRightBottomY();
			vertices[vInd + 14] = 0.0f;
			vertices[vInd + 15] = rect.getLeftTopX();
			vertices[vInd + 16] = rect.getRightBottomY();
			vertices[vInd + 17] = 0.0f;
			vertices[vInd + 18] = rect.getLeftTopX();
			vertices[vInd + 19] = rect.getRightBottomY();
			vertices[vInd + 20] = 0.0f;
			vertices[vInd + 21] = rect.getLeftTopX();
			vertices[vInd + 22] = rect.getLeftTopY();
			vertices[vInd + 23] = 0.0f;
			vInd += 24;
		}
		return vertices;
	}

	/**
	 * Vuforia utils
	 */
	
	@Override
	public void onDrawFrame(GL10 gl) {
		if (!mIsActive)
			return;
		renderFrame();
	}

	@Override
	public void onSurfaceCreated(GL10 gl, EGLConfig config) {
		Log.d(LOGTAG, "GLRenderer.onSurfaceCreated");
		initRendering();
		vuforiaAppSession.onSurfaceCreated();
	}

	@Override
	public void onSurfaceChanged(GL10 gl, int width, int height) {
		Log.d(LOGTAG, "GLRenderer.onSurfaceChanged");
		vuforiaAppSession.onSurfaceChanged(width, height);
	}
	
	public int[] getCameraDetails() {
		VideoBackgroundConfig config = mRenderer.getInstance().getVideoBackgroundConfig();
		int[] details = new int[2];
		
		details[0] = config.getSize().getData()[0];
		details[1] = config.getSize().getData()[1];
		
		return details;
	}
	
	private void setLocation(float f) {
		location = f;
	}
	
	private void resetLocation(){
		location = -10000;
	}

}
