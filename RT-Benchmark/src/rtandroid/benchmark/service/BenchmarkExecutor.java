/*
 * Copyright (C) 2015 RTAndroid Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rtandroid.benchmark.service;

import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.util.Locale;

import rtandroid.benchmark.benchmarks.Benchmark;
import rtandroid.benchmark.data.TestCase;
import rtandroid.CpuLock;
import rtandroid.IRealTimeService;
import rtandroid.RealTimeWrapper;

public class BenchmarkExecutor implements Runnable
{
    private static final String TAG = BenchmarkExecutor.class.getSimpleName();
    private static final String RESULT_FOLDER = "LatencySuite";
    private static final String FILE_TEMPLATE =  RESULT_FOLDER.toLowerCase(Locale.getDefault()) + "_b=%s_p=%d_s=%d_c=%d_case=%s.csv";
    private static final int GUI_UPDATE_TIME = 1000;

    private final Context mContext;
    private final Benchmark mBenchmark;
    private final int mParameter;
    private final int mCycles;
    private final int mSleep;
    private final TestCase mTestCase;

    private NativeLib mLib = null;
    private String mFileName = null;

    private boolean mInterrupted = false;

    public BenchmarkExecutor(Context context, Benchmark benchmark, int parameter, int cycles, int sleep, TestCase testCase)
    {
        mContext = context;
        mBenchmark = benchmark;
        mParameter = parameter;
        mCycles = cycles;
        mSleep = sleep;
        mTestCase = testCase;

        // Generate the filename
        String benchmarkName = mBenchmark.getName().replaceAll("\\s","").replace('/', '-');
        String caseName = mTestCase.getName().replaceAll("\\s","").replace('/', '-');
        String fileName = String.format(Locale.US, FILE_TEMPLATE, benchmarkName, mParameter, mSleep, mCycles, caseName);
        File resultFolder = new File(Environment.getExternalStorageDirectory(), RESULT_FOLDER);
        resultFolder.mkdirs();
        mFileName = new File(resultFolder, fileName).getAbsolutePath();

        // Create the library
        mLib = new NativeLib(mFileName);
        mInterrupted = false;
    }

    @Override
    public void run()
    {
        String msg = String.format(Locale.US, "Benchmark '%s' with case '%s' started", mBenchmark.getName(), mTestCase.getName());
        Log.d(TAG, msg);

        // We always acquire a cpu lock
        CpuLock cpuLock = new CpuLock(mContext);

        // Set everything up (power level, priority etc)
        int powerLevel = mTestCase.getPowerLevel();
        if (powerLevel != TestCase.NO_POWER_LEVEL)
        {
            Log.i(TAG, "Acquiring power lock at " + powerLevel);
            cpuLock.setPowerLevel(powerLevel);
        }

        int priority = mTestCase.getRealtimePriority();
        if (priority != TestCase.NO_REALTIME_PRIORITY)
        {
            Log.i(TAG, "Setting the RT priority to " + priority);
            int tid = android.os.Process.myTid();
            try
            {
                IRealTimeService service = RealTimeWrapper.getService();
                service.setSchedulingPolicy(tid, RealTimeWrapper.SCHED_POLICY_FIFO);
                service.setPriority(tid, priority);
            }
            catch (RemoteException e) { throw new RuntimeException(e); }
        }

        // This prevents the cpu from deep sleep even w/o locking the power level
        cpuLock.acquire();

        // Notify activity about start
        final Intent startIntent = new Intent(BenchmarkService.ACTION_START);
        startIntent.putExtra(BenchmarkService.EXTRA_TEST_CASE_NAME, mTestCase.getName());
        mContext.sendBroadcast(startIntent);

        // Perform benchmark
        long updateTimestamp = System.currentTimeMillis();
        final Intent updateIntent = new Intent(BenchmarkService.ACTION_UPDATE);

        for (int iteration = 0; (iteration < mCycles) && !mInterrupted; iteration++)
        {
            // Sleep a bit
            long sleepTimeUs = mLib.libSleep(mSleep);

            // Do actual task
            long timestamp = System.nanoTime();
            mBenchmark.execute(mParameter);
            long calcTimeUs = System.nanoTime() - timestamp;

            // Write data to file
            mLib.libWriteLong(calcTimeUs);
            mLib.libWriteLong(sleepTimeUs);
            mLib.libWriteCR();

            // Send progress to activity
            if ((System.currentTimeMillis() - updateTimestamp) >= GUI_UPDATE_TIME)
            {
                updateIntent.putExtra(BenchmarkService.EXTRA_ITERATIONS, iteration);
                mContext.sendBroadcast(updateIntent);

                updateTimestamp = System.currentTimeMillis();
            }
        }

        // Clean everything up
        cpuLock.release();

        // Notify the GUI that the worker thread is done
        final Intent finishedIntent = new Intent(BenchmarkService.ACTION_FINISHED);
        finishedIntent.putExtra(BenchmarkService.EXTRA_TEST_CASE_ID, mTestCase.getId());
        finishedIntent.putExtra(BenchmarkService.EXTRA_FILENAME, mFileName);
        mContext.sendBroadcast(finishedIntent);

        Log.d(TAG, "Benchmark terminated");
    }


    public void cancel()
    {
        mInterrupted = true;
    }

}