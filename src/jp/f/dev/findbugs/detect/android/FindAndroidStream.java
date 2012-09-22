/*
 * findbugs for android
 * Copyright (c) 2012, Hisato Shoji.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301  USA
 */

package jp.f.dev.findbugs.detect.android;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.apache.bcel.Constants;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.MethodGen;
import org.apache.bcel.generic.ObjectType;
import org.apache.bcel.generic.Type;

import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.ResourceCollection;
import edu.umd.cs.findbugs.ResourceTrackingDetector;
import edu.umd.cs.findbugs.SourceLineAnnotation;
import edu.umd.cs.findbugs.StatelessDetector;
import edu.umd.cs.findbugs.SystemProperties;
import edu.umd.cs.findbugs.TypeAnnotation;
import edu.umd.cs.findbugs.ba.CFG;
import edu.umd.cs.findbugs.ba.CFGBuilderException;
import edu.umd.cs.findbugs.ba.ClassContext;
import edu.umd.cs.findbugs.ba.Dataflow;
import edu.umd.cs.findbugs.ba.DataflowAnalysisException;
import edu.umd.cs.findbugs.ba.Hierarchy;
import edu.umd.cs.findbugs.ba.Location;
import edu.umd.cs.findbugs.ba.ObjectTypeFactory;
import edu.umd.cs.findbugs.ba.ResourceValueAnalysis;
import edu.umd.cs.findbugs.ba.ResourceValueFrame;
import edu.umd.cs.findbugs.detect.IOStreamFactory;
import edu.umd.cs.findbugs.detect.MethodReturnValueStreamFactory;
import edu.umd.cs.findbugs.detect.Stream;
import edu.umd.cs.findbugs.detect.StreamEquivalenceClass;
import edu.umd.cs.findbugs.detect.StreamFactory;
import edu.umd.cs.findbugs.detect.StreamResourceTracker;

public class FindAndroidStream extends
        ResourceTrackingDetector<Stream, StreamResourceTracker> implements
        StatelessDetector {

    private static final boolean DEBUG = SystemProperties.getBoolean("af.debug");

    static final boolean IGNORE_WRAPPED_UNINTERESTING_STREAMS = !SystemProperties.getBoolean("fos.allowWUS");

    /*
     * ----------------------------------------------------------------------
     * Tracked resource types
     * ----------------------------------------------------------------------
     */

    /**
     * List of base classes of tracked resources.
     */
    static final ObjectType[] streamBaseList = {
            ObjectTypeFactory.getInstance("android.database.Cursor"),
            ObjectTypeFactory.getInstance("java.io.InputStream"),
            ObjectTypeFactory.getInstance("java.io.OutputStream"),
            ObjectTypeFactory.getInstance("android.util.JsonReader"),
            ObjectTypeFactory.getInstance("android.util.JsonWriter"),
            ObjectTypeFactory.getInstance("android.content.res.AssetManager"),
            ObjectTypeFactory.getInstance("android.content.res.XmlResourceParser")
            };

    static {
        ArrayList<StreamFactory> streamFactoryCollection = new ArrayList<StreamFactory>();

        // TODO: Need to check related methods of Cursor.
        streamFactoryCollection
                .add(new MethodReturnValueStreamFactory(
                        "android.content.ContentResolver",
                        "query",
                        "(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;",
                        "ANDROID_UNCLOSED_CURSOR"));

        // File stream related of Application data.
        streamFactoryCollection.add(new MethodReturnValueStreamFactory(
                "android.content.Context", "openFileInput",
                "(Ljava/lang/String;)Ljava/io/FileInputStream;",
                "ANDROID_OPEN_STREAM"));
        streamFactoryCollection.add(new MethodReturnValueStreamFactory(
                "android.content.Context", "openFileOutput",
                "(Ljava/lang/String;I)Ljava/io/FileOutputStream;",
                "ANDROID_OPEN_STREAM"));
        streamFactoryCollection.add(new MethodReturnValueStreamFactory(
                "android.content.res.Resources", "openRawResource",
                "(I)Ljava/io/InputStream;",
                "ANDROID_OPEN_STREAM"));
        streamFactoryCollection.add(new MethodReturnValueStreamFactory(
                "android.content.res.Resources", "openRawResource",
                "(ILandroid/util/TypedValue;)Ljava/io/InputStream;",
                "ANDROID_OPEN_STREAM"));

        // Content Resolver related.
        streamFactoryCollection.add(new MethodReturnValueStreamFactory(
                "android.content.ContentResolver", "openInputStream",
                "(Landroid/net/Uri;)Ljava/io/InputStream;",
                "ANDROID_OPEN_STREAM"));
        streamFactoryCollection.add(new MethodReturnValueStreamFactory(
                "android.content.ContentResolver", "openOutputStream",
                "(Landroid/net/Uri;)Ljava/io/OutputStream;",
                "ANDROID_OPEN_STREAM"));
        streamFactoryCollection.add(new MethodReturnValueStreamFactory(
                "android.content.ContentResolver", "openOutputStream",
                "(Landroid/net/Uri;Ljava/lang/String;)Ljava/io/OutputStream;",
                "ANDROID_OPEN_STREAM"));

        // Json related
        streamFactoryCollection.add(new IOStreamFactory(
                "android.util.JsonReader", new String[0], "ANDROID_OPEN_STREAM"));
        streamFactoryCollection.add(new IOStreamFactory(
                "android.util.JsonWriter", new String[0], "ANDROID_OPEN_STREAM"));

        // AssetManager related.
        streamFactoryCollection.add(new MethodReturnValueStreamFactory(
                "android.content.res.Resources", "getAssets",
                "()Landroid/content/res/AssetManager;",
                "ANDROID_OPEN_STREAM"));
        streamFactoryCollection.add(new MethodReturnValueStreamFactory(
                "android.content.res.AssetManager", "open",
                "(Ljava/lang/String;)Ljava/io/InputStream;",
                "ANDROID_OPEN_STREAM"));
        streamFactoryCollection.add(new MethodReturnValueStreamFactory(
                "android.content.res.AssetManager", "open",
                "(Ljava/lang/String;I)Ljava/io/InputStream;",
                "ANDROID_OPEN_STREAM"));
        streamFactoryCollection.add(new MethodReturnValueStreamFactory(
                "android.content.res.AssetManager", "openXmlResourceParser",
                "(Ljava/lang/String;)Landroid/content/res/XmlResourceParser;",
                "ANDROID_OPEN_STREAM"));
        streamFactoryCollection.add(new MethodReturnValueStreamFactory(
                "android.content.res.AssetManager", "openXmlResourceParser",
                "(ILjava/lang/String;)Landroid/content/res/XmlResourceParser;",
                "ANDROID_OPEN_STREAM"));

        // File Descriptor related.
        //TODO need to be updated(?), check related classes.
        streamFactoryCollection.add(new MethodReturnValueStreamFactory(
                "android.content.res.AssetFileDescriptor", "createInputStream",
                "()Ljava/io/FileInputStream;",
                "ANDROID_OPEN_STREAM"));
        streamFactoryCollection.add(new MethodReturnValueStreamFactory(
                "android.content.res.AssetFileDescriptor", "createOutputStream",
                "()Ljava/io/FileOutputStream;",
                "ANDROID_OPEN_STREAM"));

        streamFactoryList = streamFactoryCollection
                .toArray(new StreamFactory[streamFactoryCollection.size()]);
    }

    /**
     * StreamFactory objects used to detect resources created within analyzed
     * methods.
     */
    static final StreamFactory[] streamFactoryList;


    /*
     * ----------------------------------------------------------------------
     * Helper classes
     * ----------------------------------------------------------------------
     */

    private static class PotentialOpenStream {
        public final String bugType;

        public final int priority;

        public final Stream stream;
        
        @Override
        public String toString() {
            return stream.toString();
        }

        public PotentialOpenStream(String bugType, int priority, Stream stream) {
            this.bugType = bugType;
            this.priority = priority;
            this.stream = stream;
        }
    }

    /*
     * ----------------------------------------------------------------------
     * Fields
     * ----------------------------------------------------------------------
     */

    private List<PotentialOpenStream> potentialOpenStreamList;

    /*
     * ----------------------------------------------------------------------
     * Implementation
     * ----------------------------------------------------------------------
     */

    public FindAndroidStream(BugReporter bugReporter) {
        super(bugReporter);
        this.potentialOpenStreamList = new LinkedList<PotentialOpenStream>();
        if (DEBUG) { System.out.println("FindAndroidStream Constructor"); }
    }

    @Override
    public Object clone() {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    @Override
    public boolean prescreen(ClassContext classContext, Method method,
            boolean mightClose) {
        if (DEBUG) { System.out.println("FindAndroidStream prescreen in"); }
        BitSet bytecodeSet = classContext.getBytecodeSet(method);
        if (DEBUG) { System.out.println("FindAndroidStream prescreen bytecodeSet = " + bytecodeSet); }
        if (DEBUG) { System.out.println("FindAndroidStream prescreen method = " + method); }

        if (bytecodeSet == null)
            return false;
         return bytecodeSet.get(Constants.NEW)
         || bytecodeSet.get(Constants.INVOKEINTERFACE)
         || bytecodeSet.get(Constants.INVOKESPECIAL)
         || bytecodeSet.get(Constants.INVOKESTATIC)
         || bytecodeSet.get(Constants.INVOKEVIRTUAL);
    }
    
    @Override
    public void analyzeMethod(ClassContext classContext, Method method, StreamResourceTracker resourceTracker,
            ResourceCollection<Stream> resourceCollection) throws CFGBuilderException, DataflowAnalysisException {

        if (DEBUG) { System.out.println("FindAndroidStream analyzeMethod in, method: " + method); }
        if (DEBUG) { System.out.println("FindAndroidStream analyzeMethod in, potentialOpenStreamList: " + potentialOpenStreamList.size()); }

        // TODO: Need to improve potentialOpenStreamList handling. Sometimes logic throw away tracking resource.
        potentialOpenStreamList.clear();

        JavaClass javaClass = classContext.getJavaClass();
        MethodGen methodGen = classContext.getMethodGen(method);
        if (methodGen == null)
            return;
        CFG cfg = classContext.getCFG(method);

        // Add Streams passed into the method as parameters.
        // These are uninteresting, and should poison
        // any streams which wrap them.
        try {
            Type[] parameterTypeList = Type.getArgumentTypes(methodGen.getSignature());
            Location firstLocation = new Location(cfg.getEntry().getFirstInstruction(), cfg.getEntry());

            int local = methodGen.isStatic() ? 0 : 1;

            for (Type type : parameterTypeList) {
                if (type instanceof ObjectType) {
                    ObjectType objectType = (ObjectType) type;
                    
                    for (ObjectType streamBase : streamBaseList) {
                        if (Hierarchy.isSubtype(objectType, streamBase)) {
                            if (DEBUG) { System.out.println("FindAndroidStream analyzeMethod OK, found a parameter that is a resource. objectType: " + objectType); }
                            // OK, found a parameter that is a resource.
                            // Create a Stream object to represent it.
                            // The Stream will be uninteresting, so it will
                            // inhibit reporting for any stream that wraps it.
                            Stream paramStream = new Stream(firstLocation, objectType.getClassName(), streamBase.getClassName());
                            paramStream.setIsOpenOnCreation(true);
                            paramStream.setOpenLocation(firstLocation);
                            paramStream.setInstanceParam(local);
                            resourceCollection.addPreexistingResource(paramStream);

                            break;
                        }
                    }
                }

                switch (type.getType()) {
                case Constants.T_LONG:
                case Constants.T_DOUBLE:
                    local += 2;
                    break;
                default:
                    local += 1;
                    break;
                }
            }
        } catch (ClassNotFoundException e) {
            bugReporter.reportMissingClass(e);
        }

        // Set precomputed map of Locations to Stream creation points.
        // That way, the StreamResourceTracker won't have to
        // repeatedly try to figure out where Streams are created.
        resourceTracker.setResourceCollection(resourceCollection);

        super.analyzeMethod(classContext, method, resourceTracker, resourceCollection);

        // Compute streams that escape into other streams:
        // this takes wrapper streams into account.
        // This will also compute equivalence classes of streams,
        // so that if one stream in a class is closed,
        // they are all considered closed.
        // (FIXME: this is too simplistic, especially if buffering
        // is involved. Sometime we should really think harder
        // about how this should work.)
        resourceTracker.markTransitiveUninterestingStreamEscapes();

        // For each stream closed on all paths, mark its equivalence
        // class as being closed.
        for (Iterator<Stream> i = resourceCollection.resourceIterator(); i.hasNext();) {
            Stream stream = i.next();
            StreamEquivalenceClass equivalenceClass = resourceTracker.getStreamEquivalenceClass(stream);
            if (stream.isClosed())
                equivalenceClass.setClosed();
        }

        // Iterate through potential open streams, reporting warnings
        // for the "interesting" streams that haven't been closed
        // (and aren't in an equivalence class with another stream
        // that was closed).
        for (PotentialOpenStream pos : potentialOpenStreamList) {
            Stream stream = pos.stream;
            if (stream.isClosed())
                // Stream was in an equivalence class with another
                // stream that was properly closed.
                continue;

            if (stream.isUninteresting())
                continue;

            Location openLocation = stream.getOpenLocation();
            if (openLocation == null)
                continue;

            if (IGNORE_WRAPPED_UNINTERESTING_STREAMS && resourceTracker.isUninterestingStreamEscape(stream))
                continue;

            String sourceFile = javaClass.getSourceFileName();
            String leakClass = stream.getStreamBase();
            if (DEBUG) { System.out.println("FindAndroidStream analyzeMethod sourceFile: " + sourceFile + " leakClass: " + leakClass); }
            if (isMainMethod(method) && (leakClass.contains("InputStream") || leakClass.contains("Reader")))
                return;

            bugAccumulator.accumulateBug(new BugInstance(this, pos.bugType, pos.priority)
                    .addClassAndMethod(methodGen, sourceFile).addTypeOfNamedClass(leakClass)
                    .describe(TypeAnnotation.CLOSEIT_ROLE), SourceLineAnnotation.fromVisitedInstruction(classContext, methodGen,
                    sourceFile, stream.getLocation().getHandle()));
        }
        if (DEBUG) { System.out.println("FindAndroidStream analyzeMethod out"); }
    }

    public static boolean isMainMethod(Method method) {
        return method.isStatic() && method.getName().equals("main") && method.getSignature().equals("([Ljava/lang/String;)V");
    }

    @Override
    public StreamResourceTracker getResourceTracker(ClassContext classContext,
            Method method) {
        if (DEBUG) { System.out.println("FindAndroidStream getResourceTracker in"); }
        return new StreamResourceTracker(streamFactoryList, bugReporter);
    }

    @Override
    public void inspectResult(
            ClassContext classContext,
            MethodGen methodGen,
            CFG cfg,
            Dataflow<ResourceValueFrame, ResourceValueAnalysis<Stream>> dataflow,
            Stream stream) {
        if (DEBUG) { System.out.println("FindAndroidStream inspectResult in"); }
        ResourceValueFrame exitFrame = dataflow.getResultFact(cfg.getExit());

        int exitStatus = exitFrame.getStatus();
        if (DEBUG) { System.out.println("FindAndroidStream inspectResult exitStatus: " + exitStatus); }
        if (exitStatus == ResourceValueFrame.OPEN || exitStatus == ResourceValueFrame.OPEN_ON_EXCEPTION_PATH) {

            // FIXME: Stream object should be queried for the
            // priority.

            String bugType = stream.getBugType();
            int priority = HIGH_PRIORITY;
            if (exitStatus == ResourceValueFrame.OPEN_ON_EXCEPTION_PATH) {
                bugType += "_EXCEPTION_PATH";
                priority = NORMAL_PRIORITY;
            }
            if (DEBUG) { System.out.println("FindAndroidStream inspectResult add stream: " + stream); }

            potentialOpenStreamList.add(new PotentialOpenStream(bugType, priority, stream));
        } else if (exitStatus == ResourceValueFrame.CLOSED) {
            // Remember that this stream was closed on all paths.
            // Later, we will mark all of the streams in its equivalence class
            // as having been closed.
            stream.setClosed();
        }
        if (DEBUG) { System.out.println("FindAndroidStream inspectResult out"); }
    }

}
