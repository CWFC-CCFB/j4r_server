/*
 * This file is part of the j4r library.
 *
 * Copyright (C) 2020-2021 Her Majesty the Queen in right of Canada
 * Author: Mathieu Fortin, Canadian Wood Fibre Centre, Canadian Forest Service.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This library is distributed with the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * Please see the license at http://www.gnu.org/copyleft/lesser.html.
 */
package j4r.lang.codetranslator;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.InvalidParameterException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.SimpleFormatter;

import j4r.app.AbstractGenericEngine;
import j4r.lang.J4RSystem;
import j4r.lang.reflect.ReflectUtility;
import j4r.multiprocess.JavaProcess;
import j4r.multiprocess.JavaProcessWrapper;
import j4r.net.server.BasicClient;
import j4r.net.server.JavaGatewayServer;
import j4r.net.server.ServerConfiguration;
import j4r.util.JarUtility;
import j4r.util.ObjectUtility;

@SuppressWarnings("serial")
public class REnvironment extends ConcurrentHashMap<Integer, Map<Integer, List<Object>>> { // first integer : hahscode, second integer : collider
	
	private static final String FIRSTCALL = "-firstcall";
	
	private static final String LOGLEVEL = "-loglevel";
	
	public static final String MainSplitter = "/;";
	
	public static final String SubSplitter = "/,";
	
	public static final String ColliderSplitter = "_";
	
	public static final String ClassPathSeparator = "::";
	
	protected static final String R_NUMERIC_TOKEN = "nu";
	protected static final String R_INTEGER_TOKEN = "in";
	protected static final String R_LOGICAL_TOKEN = "lo";
	protected static final String R_CHARACTER_TOKEN = "ch";
	protected static final String R_JAVA_OBJECT_TOKEN = "JO";
	protected static final String R_JAVA_LIST_TOKEN = "JL";
	
	protected static final String R_JAVA_OBJECT_HASHCODE_PREFIX = "java.objecthashcode";
	
	private final static Map<String, Class<?>> PrimitiveTypeMap = new HashMap<String, Class<?>>();
	static {
		PrimitiveTypeMap.put("integer", int.class);
		PrimitiveTypeMap.put("character", String.class);
		PrimitiveTypeMap.put("numeric", double.class);
		PrimitiveTypeMap.put("logical", boolean.class);
		PrimitiveTypeMap.put("long", long.class);
		PrimitiveTypeMap.put("float", float.class);
	}

	static class InternalShutDownHook extends Thread {
		
		final JavaProcessWrapper rGatewayProcessWrapper;
		
		InternalShutDownHook(JavaProcessWrapper rGatewayProcessWrapper) {
			this.rGatewayProcessWrapper = rGatewayProcessWrapper;
		}
		
		@Override
		public void run() {
			rGatewayProcessWrapper.cancel(true);
		}
		
	}

	
	static class ExecutableWrapper<P extends Executable> implements Comparable<ExecutableWrapper> {

		final double score; 
		final P executable;
		
		ExecutableWrapper(double score, P executable) {
			this.score = score;
			this.executable = executable;
		}
		
		@Override
		public int compareTo(ExecutableWrapper o) {
			if (score < o.score) {
				return -1;
			} else  if (score == o.score) {
				return 0;
			} else {
				return 1;
			}
		}
	}
	
	static class JavaObjectList extends ArrayList<ParameterWrapper> {
		
		@Override
		public String toString() {
			String output = R_JAVA_LIST_TOKEN + MainSplitter;
			for (ParameterWrapper obj : this) {
				String toBeAdded = obj.toString();
				if (toBeAdded.startsWith(R_JAVA_OBJECT_TOKEN + MainSplitter)) {
					toBeAdded = toBeAdded.substring((R_JAVA_OBJECT_TOKEN + MainSplitter).length());
				}
				output = output + toBeAdded + SubSplitter;	
			}
			return output;
		}
	}
	
	static class ParameterList extends ArrayList<List<ParameterWrapper>> {
		int getInnerSize() {
			int currentSize = 0;
			for (int i = 0; i < size(); i++) {
				if (get(i).size() > currentSize) {
					currentSize = get(i).size();
				}
			}
			return currentSize;
		}
		
		Object[] getParameterArray(int i) {
			int currentSize = getInnerSize();
			if (i > currentSize) {
				throw new InvalidParameterException("Inconsistent parameter setup!");
			}
			Object[] parms = new Object[size()];
			for (int j = 0; j < size(); j++) {
				if (get(j).size() == 1) {
					parms[j] = get(j).get(0).value;
				} else {
					parms[j] = get(j).get(i).value;
				}
			}
			return parms;
		}
	}
	
	protected class ParameterWrapper {
		
		final Class<?> type;
		final Object value;
		final int colliderInt;
		
		/**
		 * Complete constructor for callback to R.
		 * @param type
		 * @param value
		 * @param colliderInt
		 */
		private ParameterWrapper(Class<?> type, Object value, int colliderInt) {
			this.type = type;
			this.value = value;
			this.colliderInt = colliderInt;
		}

		/**
		 * Temporary wrapper for internal use.
		 * @param type
		 * @param value
		 */
		private ParameterWrapper(Class<?> type, Object value) {
			this(type, value, 0);
		}
		
		@Override
		public String toString() {
			if (ReflectUtility.JavaWrapperToPrimitiveMap.containsKey(type)) {
				if (type.equals(Double.class) || type.equals(Float.class)) {
					return R_NUMERIC_TOKEN + ((Number) value).toString();
				} else if (type.equals(Integer.class) || type.equals(Long.class)) {
					return R_INTEGER_TOKEN + ((Number) value).toString();
				} else if (type.equals(Boolean.class)) {
					return R_LOGICAL_TOKEN + ((Boolean) value).toString();
				} else {
					return R_CHARACTER_TOKEN + value.toString();
				}
			} else {
				String className = type.getName();
				if (className.endsWith(";")) {
					className = className.substring(0, className.length() - 1);
				} else if (className.endsWith(MainSplitter)) {
					className = className.substring(0, className.length() - MainSplitter.length());
				}
				return R_JAVA_OBJECT_TOKEN + MainSplitter + className + "@" + System.identityHashCode(value) + "_" + colliderInt;
			}
		}
	}

	
	private class NullWrapper {

		final Class<?> type; 
		
		private NullWrapper(Class<?> type) {
			this.type = type;
		}
		
	}
		
	protected final static String ConstructCode = "co";
	protected final static String ConstructNullArrayCode = "cona";
	protected final static String ConstructNullCode = "conu";
	protected final static String ConstructArrayCode = "coar";
	protected final static String MethodCode = "method";
	protected final static String ClassInfo = "cli";
	protected final static String FlushInstances = "flush";
	protected final static String InternalMapSize = "size";
	protected final static String FieldCode = "field";

	private static final Random RANDOM = new Random();
	

	/**
	 * Process the request.
	 * @param request a String 
	 * @return an Object
	 * @throws Exception
	 */
	public Object processCode(String request) throws Exception {
		AbstractGenericEngine.J4RLogger.log(Level.FINE, "Processing request: " + request);
		String[] requestStrings = request.split(MainSplitter);
		if (requestStrings[0].startsWith(ConstructCode)) {	// can be either create, createarray or createnull here
			return createObjectFromRequestStrings(requestStrings); 
		} else if (requestStrings[0].equals(MethodCode)) {
			return processMethod(requestStrings);
		} else if (requestStrings[0].equals(FieldCode)) {
			return processField(requestStrings);
		} else if (requestStrings[0].equals(ClassInfo)) {
			return getClassInfo(requestStrings);
		} else if (requestStrings[0].equals(FlushInstances)) {
			return flushTheseObjects(requestStrings);
		} else if (requestStrings[0].equals(InternalMapSize)) {
			return getInternalMapSize();
		} else {
			try {
				return BasicClient.ClientRequest.valueOf(request);
			} catch (Exception e) {
				throw new InvalidParameterException("Request unknown! " + request);
			}
		}
	}
	

	private Object getClassInfo(String[] requestStrings) throws ClassNotFoundException {
		String classname = requestStrings[1];
		Class clazz;
		boolean isArray;
		if (classname.startsWith("[")) {
			clazz = Object.class;
			isArray = true;
		} else {
			clazz = Class.forName(classname);
			isArray = false;
		}
		Method[] methods = clazz.getMethods();
		JavaObjectList outputList = new JavaObjectList();
		for (Method m : methods) {
			registerMethodOutput(m.getName(), outputList);
		}
		if (isArray) {
			registerMethodOutput("clone", outputList); // clone is changed from protected to public when dealing with arrays
		}
		registerMethodOutput("endOfMethods", outputList);
		Field[] fields = clazz.getFields();
		for (Field f : fields) {
			registerMethodOutput(f.getName(), outputList);
		}
		if (isArray) {
			registerMethodOutput("length", outputList);
		}
		if (outputList.isEmpty()) {
			return null;
		} else if (outputList.size() == 1) {
			return outputList.get(0);
		} else {
			return outputList;
		}
	}


	private Object flushTheseObjects(String[] requestStrings) {
		String prefix = R_JAVA_OBJECT_HASHCODE_PREFIX;
		if (requestStrings[1].startsWith(prefix)) {
			String[] newArgs = requestStrings[1].substring(prefix.length()).split(SubSplitter);
			for (int i = 0; i < newArgs.length; i++) {
				String[] hashcodeAndColliderForThisJavaObject = newArgs[i].split(ColliderSplitter);
				int hashcodeForThisJavaObject = Integer.parseInt(hashcodeAndColliderForThisJavaObject[0]);
				int collider = Integer.parseInt(hashcodeAndColliderForThisJavaObject[1]);
				if (containsKey(hashcodeForThisJavaObject)) {
					Map<Integer, List<Object>> innerMap = get(hashcodeForThisJavaObject);
					List<Object> innerList = innerMap.get(collider);
					innerList.remove(0);
					if (innerList.isEmpty()) {
						innerMap.remove(collider);
						if (innerMap.isEmpty()) {
							remove(hashcodeForThisJavaObject);
						}
					}
				}
			}
		}
		return null;
	}

	private JavaObjectList getInternalMapSize() {
		JavaObjectList outputList = new JavaObjectList();
		registerMethodOutput(size(), outputList);
		return outputList;
	}
	
	protected List<ParameterWrapper> findObjectInEnvironment(String string) {
		List<ParameterWrapper> wrappers = new ArrayList<ParameterWrapper>();
		String prefix = R_JAVA_OBJECT_HASHCODE_PREFIX;
		if (string.startsWith(prefix)) {
			String[] newArgs = string.substring(prefix.length()).split(SubSplitter);
			for (int i = 0; i < newArgs.length; i++) {
				String[] hashcodePlusColliderForThisJavaObject = newArgs[i].split(ColliderSplitter);
				int hashcodeForThisJavaObject = Integer.parseInt(hashcodePlusColliderForThisJavaObject[0]);
				int collider = Integer.parseInt(hashcodePlusColliderForThisJavaObject[1]);
				if (containsKey(hashcodeForThisJavaObject)) {
					Map<Integer, List<Object>> innerMap = get(hashcodeForThisJavaObject);
					Object value = innerMap.get(collider).get(0);
					Class<?> type;
					if (value instanceof NullWrapper) {
						type = ((NullWrapper) value).type;
						value = null;
					} else {
						type = value.getClass();
					}
					wrappers.add(new ParameterWrapper(type, value));
				} else {
					throw new InvalidParameterException("This object does not exist: " + string);
				}
			}
		}
		return wrappers;
	}

	
	@SuppressWarnings({ "rawtypes"})
	private Object processField(String[] requestStrings) throws Exception {
		Class clazz = null;
		List<ParameterWrapper> wrappers = null;
		boolean lookingForStaticMethod = false;
		if (requestStrings[1].startsWith("java.object")) {			// presumably non-static method
			wrappers = findObjectInEnvironment(requestStrings[1]);
			Object caller = getCallerAmongWrappers(wrappers);
			clazz = caller.getClass();
		} else {
			wrappers = createFromPrimitiveClass(getPrimitiveClass(requestStrings[1]), requestStrings[1]);
			ParameterWrapper caller = wrappers.get(0);
			if (wrappers.size() == 1 && caller.type.equals(String.class)) { // could be a call to a static method
				try {
					String className = caller.value.toString();
					clazz = Class.forName(className);
					lookingForStaticMethod = true;
					wrappers = new ArrayList<ParameterWrapper>();
					wrappers.add(new ParameterWrapper(clazz, null));
				} catch (ClassNotFoundException e) {
					clazz = ReflectUtility.PrimitiveToJavaWrapperMap.get(caller.type);
				}
			} else {
				clazz = ReflectUtility.PrimitiveToJavaWrapperMap.get(caller.type);
			}
		}
		List[] outputLists = marshallParameters(requestStrings, 3);
		List<Class<?>> parameterTypes = outputLists[0];
		if (parameterTypes.size() > 1) {
			throw new InvalidParameterException("While setting a field, there cannot be more than a single argument to the function!");
		}
		ParameterList parameters = (ParameterList) outputLists[1];
		String fieldName = requestStrings[2];
		boolean isArrayLengthCalled = clazz.getName().startsWith("[") && parameters.isEmpty() && fieldName.equals("length");
		Field field = null;
		if (!isArrayLengthCalled) {
			try {
				field = clazz.getField(fieldName);
			} catch (NoSuchFieldException e) {
				if (clazz.equals(String.class)) {
					throw new NoSuchFieldException(e.getMessage() + " - NOTE: the source was treated as a String object!");
				} else {
					throw e;
				}
			} 			

			if (lookingForStaticMethod) {
				if (!Modifier.isStatic(field.getModifiers())) {		// checks if the field is truly static or throws an exception otherwise
					throw new InvalidParameterException("The field is not a static field!");
				}
			}
		}
		
		JavaObjectList outputList = new JavaObjectList();
		if (parameters.isEmpty()) {		// that is a getField call
			for (int j = 0; j < wrappers.size(); j++) {
				Object result;
				if (isArrayLengthCalled) {
					result = Array.getLength(wrappers.get(j).value);
				} else {
				 	result = field.get(wrappers.get(j).value);
				}
				registerMethodOutput(result, outputList);
			}
		} else {						// that is a setField call
			if (wrappers.size() > 1 && parameters.getInnerSize() > 1 && wrappers.size() != parameters.getInnerSize()) {
				throw new InvalidParameterException("The length of the java.arraylist object is different of the length of the vectors in the parameters!");
			} else {
				int maxSize = Math.max(wrappers.size(), parameters.getInnerSize());
				for (int i = 0; i < maxSize; i++) {
					int j = i;
					if (parameters.getInnerSize() == 1) {
						j = 0;
					}
					int k = i;
					if (wrappers.size() == 1) {
						k = 0;
					}
					field.set(wrappers.get(k).value, parameters.getParameterArray(j)[0]);
				}		
			}
		}
		if (outputList.isEmpty()) {
			return null;
		} else if (outputList.size() == 1) {
			return outputList.get(0);
		} else {
			return outputList;
		}
	}

	private static int generateSecurityKey() {
		return RANDOM.nextInt();
	}
	
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private Object processMethod(String[] requestStrings) throws Exception {
		Class clazz = null;
		List<ParameterWrapper> wrappers = null;
		boolean lookingForStaticMethod = false;
		if (requestStrings[1].startsWith("java.object")) {			// presumably non-static method
			wrappers = findObjectInEnvironment(requestStrings[1]);
			Object caller = getCallerAmongWrappers(wrappers);
			clazz = caller.getClass();
		} else {
			wrappers = createFromPrimitiveClass(getPrimitiveClass(requestStrings[1]), requestStrings[1]);
			ParameterWrapper caller = wrappers.get(0);
			if (wrappers.size() == 1 && caller.type.equals(String.class)) { // could be a call to a static method
				try {
					String className = caller.value.toString();
					clazz = Class.forName(className);
					lookingForStaticMethod = true;
					wrappers = new ArrayList<ParameterWrapper>();
					wrappers.add(new ParameterWrapper(clazz, null));
				} catch (ClassNotFoundException e) {
					clazz = ReflectUtility.PrimitiveToJavaWrapperMap.get(caller.type);
				}
			} else {
				clazz = ReflectUtility.PrimitiveToJavaWrapperMap.get(caller.type);
			}
		}
		List[] outputLists = marshallParameters(requestStrings, 3);
		List<Class<?>> parameterTypes = outputLists[0];
		ParameterList parameters = (ParameterList) outputLists[1];
		String methodName = requestStrings[2];
		Method met;
		try {
			if (parameters.isEmpty()) {
				met = clazz.getMethod(methodName, (Class[]) null);
			} else {
				met = clazz.getMethod(methodName, parameterTypes.toArray(new Class[]{}));
			}
		} catch (NoSuchMethodException e) {		
			if (parameters.isEmpty()) {
				throw e;
			} else {	// the exception might arise from the fact that the types are from derived classes
				met = findNearestMethod(clazz, methodName, parameterTypes);
			}
		} 			
		met.setAccessible(true);	// must be accessible in some circumstances such as size() for Arrays$ArrayList

		if (lookingForStaticMethod) {	
			if (!Modifier.isStatic(met.getModifiers())) {		// checks if the method is truly static or throws an exception otherwise
				throw new InvalidParameterException("The method is not a static method!");
			}
		}
		
		JavaObjectList outputList = new JavaObjectList();
		if (parameters.isEmpty()) {
			for (int j = 0; j < wrappers.size(); j++) {
				Object result = met.invoke(wrappers.get(j).value, (Object[]) null);
				registerMethodOutput(result, outputList);
			}
		} else {
			if (wrappers.size() > 1 && parameters.getInnerSize() > 1 && wrappers.size() != parameters.getInnerSize()) {
				throw new InvalidParameterException("The length of the java.arraylist object is different of the length of the vectors in the parameters!");
			} else {
				int maxSize = Math.max(wrappers.size(), parameters.getInnerSize());
				for (int i = 0; i < maxSize; i++) {
					int j = i;
					if (parameters.getInnerSize() == 1) {
						j = 0;
					}
					int k = i;
					if (wrappers.size() == 1) {
						k = 0;
					}
					Object result = met.invoke(wrappers.get(k).value, parameters.getParameterArray(j));
					registerMethodOutput(result, outputList);
				}		
			}
		}
		if (outputList.isEmpty()) {
			return null;
		} else if (outputList.size() == 1) {
			return outputList.get(0);
		} else {
			return outputList;
		}
	}

	private Object getCallerAmongWrappers(List<ParameterWrapper> wrappers) {
		if (wrappers == null || wrappers.isEmpty()) {
			return null;
		} else {
			Object higherLevelObject = null;
			for (ParameterWrapper wrapper : wrappers) {
				if (higherLevelObject == null) {
					higherLevelObject = wrapper.value;
				} else {
					Object newValue = wrapper.value;
					if (newValue.getClass().isAssignableFrom(higherLevelObject.getClass())) {
						higherLevelObject = newValue;
					}
				}
			}
			return higherLevelObject;
		}
	}

	@SuppressWarnings("rawtypes")
	private Method findNearestMethod(Class clazz, String methodName, List<Class<?>> parameterTypes) throws NoSuchMethodException {
		Method[] methods = clazz.getMethods();
		List<ExecutableWrapper<Method>> possibleMatches = new ArrayList<ExecutableWrapper<Method>>();
		for (Method method : methods) {
			if (method.getName().equals(methodName)) {	// possible match
				Class[] classes = method.getParameterTypes();
				double score = doParameterTypesMatch(classes, parameterTypes.toArray(new Class[]{}));
				if (score >= 0) {
					possibleMatches.add(new ExecutableWrapper<Method>(score, method));
				}
			}
		}
		if (possibleMatches.isEmpty()) {
			throw new NoSuchMethodException("Method " + methodName + " cannot be found in the class " + clazz.getSimpleName());
		} else {
			Collections.sort(possibleMatches);
		}
		return possibleMatches.get(0).executable;
	}
	
	private void registerMethodOutput(Object result, JavaObjectList outputList) {
		if (result != null) {
			if (!ReflectUtility.JavaWrapperToPrimitiveMap.containsKey(result.getClass())) {
				int collider = registerInMap(result);
				outputList.add(new ParameterWrapper(result.getClass(), result, collider));
			} else {
				outputList.add(new ParameterWrapper(result.getClass(), result));
			}
		} 
	}
	
	/**
	 * Register the object in the pointer map and returns the collider integer. This integer
	 * prevents from hash collision in the map.
	 * @param result the object to be stored
	 * @return an integer
	 */
	private int registerInMap(Object result) {
		int hashCode = System.identityHashCode(result);
		if (!containsKey(hashCode)) {
			put(hashCode, new HashMap<Integer, List<Object>>());
		}
		Map<Integer, List<Object>> innerMap = get(hashCode);
		List<Object> refList;
		if (innerMap.isEmpty()) {		// a newly created map
			int collider = 1;
			refList = new ArrayList<Object>();
			refList.add(result);
			innerMap.put(collider, refList);
			return collider;
		} else {						// the map already contains something
			int maxKey = 0;
			for (Integer key : innerMap.keySet()) {
				if (key > maxKey) {
					maxKey = key;
				}
				refList = innerMap.get(key);
				if (refList.get(0).equals(result)) {  // we are dealing with a reference to a previously stored object
					refList.add(result);
					return key;
				}
			}
			refList = new ArrayList<Object>();		// if we get here, this means that there was a hash collision
			refList.add(result);
			int collider = maxKey + 1;
			innerMap.put(collider, refList);
			return collider;
		}
	}
	
	private double doParameterTypesMatch(Class<?>[] ref, Class<?>[] obs) {
		if (ref == null && obs == null) {
			return 0d;
		} else if (ref != null && obs != null) {
			if (ref.length == obs.length) {
				double sumScores = 0d;
				for (int i = 0; i < ref.length; i++) {
					double score = isAssignableOfThisClass(ref[i], obs[i]);
					if (score == -1d) {
						return -1d;
					} else {
						sumScores += score;
					}
				}
				return sumScores;
			}
		}
		return -1d;
	}

	private boolean implementThisClassAsAnInterface(Class<?> refcl1, Class<?> cl) {
		if (ReflectUtility.JavaWrapperToPrimitiveMap.containsKey(refcl1)) {
			if (cl.equals(ReflectUtility.JavaWrapperToPrimitiveMap.get(refcl1))) {
				return true;
			}
		}
		if (ReflectUtility.PrimitiveToJavaWrapperMap.containsKey(refcl1)) {
			if (cl.equals(ReflectUtility.PrimitiveToJavaWrapperMap.get(refcl1))) {
				return true;
			}
		}
		Class<?>[] interfaces = cl.getInterfaces();
		for (Class<?> inter : interfaces) {
			if (inter.getName().equals(refcl1.getName())) {
				return true;
			}
		}
		return false;
	}
	
	private double isAssignableOfThisClass(Class<?> refcl1, Class<?> cl2) {
		int degree = 0;
		Class<?> cl = cl2;
		boolean isInterfaceMatching = implementThisClassAsAnInterface(refcl1, cl);
		while (!refcl1.getName().equals(cl.getName()) && !isInterfaceMatching && !cl.getName().equals("java.lang.Object")) {
			if (cl.isPrimitive()) {	// the superclass of primitive is assumed to be java.lang.Object
				cl = Object.class;
			} else {
				cl = cl.getSuperclass();
			}
			isInterfaceMatching = implementThisClassAsAnInterface(refcl1, cl);
			degree++;
		}
		if (refcl1.getName().equals(cl.getName())) {
			return degree;
		} else if (isInterfaceMatching) {
			return degree + .5;
		} else {
			return -1;
		}
	}
	
	@SuppressWarnings("rawtypes")
	private Constructor findNearestConstructor(Class clazz, List<Class<?>> parameterTypes) throws NoSuchMethodException {
		Constructor[] constructors = clazz.getConstructors();
		List<ExecutableWrapper<Constructor>> possibleMatches = new ArrayList<ExecutableWrapper<Constructor>>();
		for (Constructor constructor : constructors) {
			Class[] classes = constructor.getParameterTypes();
			double score = doParameterTypesMatch(classes, parameterTypes.toArray(new Class[]{}));
			if (score >= 0) {
				possibleMatches.add(new ExecutableWrapper<Constructor>(score, constructor));
			}
		}
		if (possibleMatches.isEmpty()) {
			throw new NoSuchMethodException("No public constructor was found in the class " + clazz.getSimpleName());
		} else {
			Collections.sort(possibleMatches);
		}
		return possibleMatches.get(0).executable;
	}

	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private Object createObjectFromRequestStrings(String[] requestStrings) throws Exception {
		JavaObjectList outputList = new JavaObjectList();
		
		boolean isNull = requestStrings[0].equals(ConstructNullCode);
		boolean isArray = requestStrings[0].equals(ConstructArrayCode);
		boolean isNullArray = requestStrings[0].equals(ConstructNullArrayCode);
		
		String className = requestStrings[1];
		Class<?> clazz;
		if (ReflectUtility.PrimitiveTypeMap.containsKey(className)) {
			clazz = ReflectUtility.PrimitiveTypeMap.get(className);
		} else {
			clazz = Class.forName(className);
		}
		
		List[] outputLists = marshallParameters(requestStrings, 2);
		List<Class<?>> parameterTypes = outputLists[0];
		ParameterList parameters = (ParameterList) outputLists[1];
		
		if (parameters.isEmpty()) { // constructor with no argument then
			Object newInstance;
			if (isNull) {
				newInstance = new NullWrapper(clazz);
			} else {
				newInstance = getNewInstance(isArray, clazz, null, null);
			}
			registerNewInstance(newInstance, outputList);
		} else {
			for (int i = 0; i < parameters.getInnerSize(); i++) {
				Object newInstance;
				if (isNull) {
					newInstance = new NullWrapper(clazz);
				} else if (isNullArray) {
					Object fakeInstance = getNewInstance(true, clazz, parameterTypes.toArray(new Class[]{}), parameters.getParameterArray(i));	// true: is array
					newInstance = new NullWrapper(fakeInstance.getClass());
				} else {
					newInstance = getNewInstance(isArray, clazz, parameterTypes.toArray(new Class[]{}), parameters.getParameterArray(i));
				}
				registerNewInstance(newInstance, outputList);
			}
		}
		if (outputList.isEmpty()) {
			return null;
		} else if (outputList.size() == 1) {
			return outputList.get(0);
		} else {
			return outputList;
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private Object getNewInstance(boolean isArray, Class clazz, Class[] paramTypes, Object[] paramValues) throws Exception {
		if (paramTypes == null) {
			if (isArray) {
				throw new InvalidParameterException("Constructing an array requires at least one parameter, namely an integer that determines the size of the array!");
			}
			return clazz.newInstance();
		} else {
			if (isArray) {
				int[] dimensions = (int[]) ReflectUtility.convertArrayType(paramValues, int.class);
				return Array.newInstance(clazz, dimensions);
			}
			if (clazz.isEnum()) {
				Method met = clazz.getMethod("valueOf", String.class);
				return met.invoke(null, paramValues[0].toString());
			} else {
				Constructor<?> constructor;
				try {
					constructor = clazz.getConstructor(paramTypes);
				} catch (NoSuchMethodException e) {		
					constructor = findNearestConstructor(clazz, Arrays.asList(paramTypes));		// TODO test this code
				} 			
				return constructor.newInstance(paramValues);
			}
		}
	}
	
	
	private void registerNewInstance(Object newInstance, JavaObjectList outputList) {
		int collider = registerInMap(newInstance);
		outputList.add(new ParameterWrapper(newInstance.getClass(), newInstance, collider));
	}
	
	
	@SuppressWarnings("rawtypes")
	private List[] marshallParameters(String[] args, int start) {
		List[] outputLists = new List[2];
		List<Class<?>> parameterTypes = new ArrayList<Class<?>>();
		ParameterList parameters = new ParameterList();
		outputLists[0] = parameterTypes;
		outputLists[1] = parameters;
		for (int i = start; i < args.length; i++) {
			List<ParameterWrapper> subparametersList;
			String primitiveClassIfAny = getPrimitiveClass(args[i]);
			if (primitiveClassIfAny != null) {
				subparametersList = createFromPrimitiveClass(primitiveClassIfAny, args[i]);
			} else {
				subparametersList = findObjectInEnvironment(args[i]);
			}
			parameterTypes.add(subparametersList.get(0).type);
			parameters.add(subparametersList);
		}
		return outputLists;
	}

	private String getPrimitiveClass(String string) {
		for (String key : PrimitiveTypeMap.keySet()) {
			if (string.startsWith(key)) {
				return key;
			}
		}
		return null;
	}

	
	private List<ParameterWrapper> createFromPrimitiveClass(String primitiveTypeClass, String args) {
		List<ParameterWrapper> wrappers = new ArrayList<ParameterWrapper>();
		String[] newArgs = args.substring(primitiveTypeClass.length()).split(SubSplitter);
		for (String value : newArgs) {
			if (primitiveTypeClass == "character") {
				wrappers.add(new ParameterWrapper(String.class, value));
			} else if (primitiveTypeClass == "numeric") {
				wrappers.add(new ParameterWrapper(double.class, Double.parseDouble(value)));
			} else if (primitiveTypeClass == "integer") {
				wrappers.add(new ParameterWrapper(int.class, Integer.parseInt(value)));
			} else if (primitiveTypeClass == "logical") {
				String subString = value.toLowerCase();
				wrappers.add(new ParameterWrapper(boolean.class, Boolean.valueOf(subString).booleanValue()));
			} else if (primitiveTypeClass == "long") {
				wrappers.add(new ParameterWrapper(long.class, Long.parseLong(value)));
			} else if (primitiveTypeClass == "float") {
				wrappers.add(new ParameterWrapper(float.class, Float.parseFloat(value)));
			}
		}
		return wrappers;
	}

	private static int[] parsePorts(String str) {
		String[] p = str.split(JavaGatewayServer.PortSplitter);
		int[] ports = new int[p.length];
		for (int i = 0; i < p.length; i++) {
			ports[i] = Integer.parseInt(p[i]);
		}
		return ports;
	}
	
	private static void createLogFile(boolean isFirstCall, String wd) {
		DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy_MM_dd");  
		LocalDateTime now = LocalDateTime.now();  
		File f;
		if (isFirstCall) {
			f = new File(System.getProperty("java.io.tmpdir") + File.separator + "J4RServerStarter" + dtf.format(now) + ".log");
		} else {
			if (wd != null && new File(wd).isDirectory()) {
				f = new File(wd + File.separator + "J4RServer" + dtf.format(now) + ".log");
			} else {
				f = new File(System.getProperty("java.io.tmpdir") + File.separator + "J4RServer" + dtf.format(now) + ".log");
			}
		}
		if (f.exists())
			f.delete();
		try {
			FileHandler fh = new FileHandler(f.getAbsolutePath());  
		    AbstractGenericEngine.J4RLogger.addHandler(fh);
	        SimpleFormatter formatter = new SimpleFormatter();  
	        fh.setFormatter(formatter);  
		} catch (Exception e) {
			AbstractGenericEngine.J4RLogger.log(Level.SEVERE, "Unable to create log file!");
		}
	}
	
	
	/**
	 * Main entry point for creating a REnvironment hosted by a Java local gateway server. <br>
	 * <br>
	 * To define the classpath, use the "-ext" plus the different paths separated by the REnvironment.ClassPathSeparator 
	 * variable (e.g. <code> -ext C:\myExtention\*::C:\myClasses\</code>).
	 * 
	 * @param args
	 * @throws Exception
	 */
	public static void main(String[] args) throws Exception {
		if (args == null || args.length == 0) {
			System.out.println("Usage: [-firstcall <value>] [-ports <value>] [-backdoorport <value>]");
			System.out.println("       [-key <value>] [-public <value>] [-loglevel <value>]");
			System.out.println("");
			System.out.println("   firstcall     either true or false");
			System.out.println("   ports         one positive integer or a series of integer separated by :");
			System.out.println("                 e.g. 18000:18001");
			System.out.println("   backdoorport  two integers seperated by :   e.g. 50000:50001");
			System.out.println("   key           an integer");
			System.out.println("   public        either on or off");
			System.out.println("   loglevel      INFO, FINE, FINER, FINEST");
			return;
		}
		JavaGatewayServer server = null;
		try {
			List<String> arguments = J4RSystem.setClassicalOptions(args);
			String firstCall = J4RSystem.retrieveArgument(FIRSTCALL, arguments);
			String logLevel = J4RSystem.retrieveArgument(LOGLEVEL, arguments);
			if (logLevel != null) {
				Level l = null;
				try {
					l = Level.parse(logLevel.toUpperCase());
					AbstractGenericEngine.J4RLogger.setLevel(l);
				} catch(Exception e) {
					AbstractGenericEngine.J4RLogger.log(Level.SEVERE, "Unable to set this log level: " + logLevel);
				}
			}
			if (firstCall != null && firstCall.toLowerCase().trim().equals("true")) {
				createLogFile(true, null);
				List<String> newCommands = new ArrayList<String>();
				newCommands.add(REnvironment.class.getName());
				String jarFilename = JarUtility.getJarFileIAmInIfAny(REnvironment.class);
				String classPath = jarFilename.substring(jarFilename.lastIndexOf(ObjectUtility.PathSeparator) + 1); 
				AbstractGenericEngine.J4RLogger.log(Level.INFO, "ClassPath = " + jarFilename);
				
				String extensionPath = J4RSystem.retrieveArgument(JavaGatewayServer.EXTENSION, arguments);
				if (extensionPath != null) {
					StringBuilder sb = new StringBuilder();
					String[] classPaths = extensionPath.split(REnvironment.ClassPathSeparator);
					for (int i = 0; i < classPaths.length; i++) {
						sb.append(i == 0 ? classPaths[i] : File.pathSeparatorChar + classPaths[i]);
					}
					classPath  = classPath + File.pathSeparatorChar + sb.toString();
				}
				
				String port = J4RSystem.retrieveArgument(JavaGatewayServer.PORT, arguments);
				if (port != null) {
					String[] p = port.split(JavaGatewayServer.PortSplitter);
					for (int i = 0; i < p.length; i++) {
						if (Integer.parseInt(p[i]) < 0) {
							throw new InvalidParameterException("Port numbers should be integers equal to or greater than 0!");
						};
					}
					newCommands.add(JavaGatewayServer.PORT);
					newCommands.add(port);
				} else {
					String nbPorts = J4RSystem.retrieveArgument(JavaGatewayServer.NB_PORTS, arguments);
					if (nbPorts == null) { // make sure there is at least one port
						newCommands.add(JavaGatewayServer.NB_PORTS);
						newCommands.add("1");
					} else {
						newCommands.add(JavaGatewayServer.NB_PORTS);
						newCommands.add(nbPorts);
					}
				}

				String backdoorport = J4RSystem.retrieveArgument(JavaGatewayServer.BACKDOORPORT, arguments);
				if (backdoorport != null) {
					String[] p = backdoorport.split(JavaGatewayServer.PortSplitter);
					if (p.length != 2) {
						throw new InvalidParameterException("There should be two backdoor ports!");
					}
					for (int i = 0; i < p.length; i++) {
						if (Integer.parseInt(p[i]) < 0) {
							throw new InvalidParameterException("Backdoor port numbers should be integers equal to or greater than 0!");
						};
					}
					newCommands.add(JavaGatewayServer.BACKDOORPORT);
					newCommands.add(backdoorport);
				}
				
				String wd = J4RSystem.retrieveArgument(JavaGatewayServer.WD, arguments);
				newCommands.add(JavaGatewayServer.WD);
				newCommands.add(wd);

				String memorySizeStr = J4RSystem.retrieveArgument(JavaGatewayServer.MEMORY, arguments);
				Integer memorySize = null;
				if (memorySizeStr != null) {
					try {
						memorySize = Integer.parseInt(memorySizeStr);
					} catch (Exception e) {
						memorySize = null;
					}
				}
				
				String headlessModeStr = J4RSystem.retrieveArgument(JavaGatewayServer.HEADLESS, arguments);
				boolean headlessMode = true;
				if (headlessModeStr != null && headlessModeStr.equals("false")) {
					headlessMode = false;
				}
				
				File jarFile = new File(REnvironment.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath());
				File rootPath = jarFile.getParentFile();

				JavaProcessWrapper rGatewayProcessWrapper = new JavaProcessWrapper("Java server", newCommands, rootPath);
				Runtime.getRuntime().addShutdownHook(new InternalShutDownHook(rGatewayProcessWrapper));
				JavaProcess rGatewayProcess = rGatewayProcessWrapper.getInternalProcess();
				rGatewayProcess.setClassPath(classPath);
				if (memorySize != null) {
					rGatewayProcess.setJVMMemory(memorySize);
				}
				if (headlessMode) {
					rGatewayProcess.setHeadless(true);
				}
				if (J4RSystem.isCurrentJVMLaterThanThisVersion("1.8")) {
					rGatewayProcess.setOpenModuleForVersionsLaterThan8Enabled(true);
				}
				rGatewayProcessWrapper.run();
				System.exit(0);
			}
			String portStr = J4RSystem.retrieveArgument(JavaGatewayServer.PORT, arguments);
			int[] listeningPorts;
			if (portStr != null) {
				listeningPorts = parsePorts(portStr);
			} else {
				String nbPortsStr = J4RSystem.retrieveArgument(JavaGatewayServer.NB_PORTS, arguments);
				int nbPorts;
				if (nbPortsStr == null) {
					nbPorts = 1;
				} else {
					nbPorts = Integer.parseInt(nbPortsStr);
				}
				listeningPorts = new int[nbPorts];
			}
			String wd = J4RSystem.retrieveArgument(JavaGatewayServer.WD, arguments);
			createLogFile(false, wd);
			String backdoorportStr = J4RSystem.retrieveArgument(JavaGatewayServer.BACKDOORPORT, arguments);
			int[] backdoorports;
			if (backdoorportStr != null) {
				backdoorports = parsePorts(backdoorportStr);
			} else {
				backdoorports = new int[2];
			}
			String publicMode = J4RSystem.retrieveArgument(JavaGatewayServer.PUBLIC, arguments);
			int key;
			boolean isPublic = false;
			if (publicMode != null && publicMode.trim().toLowerCase().equals("on")) {
				isPublic = true;
				String keyStr = J4RSystem.retrieveArgument(JavaGatewayServer.KEY, arguments);
				key = Integer.parseInt(keyStr);
			} else {
				key = generateSecurityKey();
			}
			ServerConfiguration conf;
			if (isPublic) {
 				conf = new ServerConfiguration(1, 10, listeningPorts, backdoorports, key);
			} else {
 				conf = new ServerConfiguration(listeningPorts, backdoorports, key, wd);
			}
			server = new JavaGatewayServer(conf, null);	// null: no need for a main instance 
			server.startApplication();
		} catch (Exception e) {
			System.err.println("Error:" + e.getMessage());
			System.exit(1);
		}
	}
	
	
}
