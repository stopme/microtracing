package com.microtracing.logagent;
import java.io.ByteArrayInputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.reflect.Modifier;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.microtracing.logagent.injectors.CallTimingInjector;
import com.microtracing.logagent.injectors.ExceptionInjector;
import com.microtracing.logagent.injectors.HttpURLConnectionRecvInjector;
import com.microtracing.logagent.injectors.HttpURLConnectionSendInjector;
import com.microtracing.logagent.injectors.JdbcExecuteInjector;
import com.microtracing.logagent.injectors.JdbcStatementInjector;
import com.microtracing.logagent.injectors.LogInjector;
import com.microtracing.logagent.injectors.MethodTimingInjector;
import com.microtracing.logagent.injectors.ServletServiceInjector;
import com.microtracing.logagent.injectors.SpanCallInjector;
import com.microtracing.logagent.injectors.SpanMethodInjector;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.ClassClassPath;
import javassist.NotFoundException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
public class LogTraceTransformer  implements ClassFileTransformer{
	//private static final org.slf4j.Logger logger =  org.slf4j.LoggerFactory.getLogger(LogTraceTransformer.class);
	private static final java.util.logging.Logger logger = java.util.logging.Logger.getLogger(LogTraceTransformer.class.getName());
			
	private LogTraceConfig config;
	
	private List<ClassInjector> classInjectors = new ArrayList<ClassInjector>();
	private List<CallInjector> callInjectors = new ArrayList<CallInjector>();
	private List<MethodInjector> methodInjectors = new ArrayList<MethodInjector>();
	
	public LogTraceTransformer(LogTraceConfig config){
		this.config = config;
		initInjectors();
	}
	
	private void addOnce(List list, Object item){
		if (!list.contains(item)) list.add(item);
	}

	private void initInjectors() {
		LogInjector logInjector = new LogInjector(config);
		//CallTimingInjector callTimeingInjector = new CallTimingInjector(config);
		MethodTimingInjector methodTimeingInjector = new MethodTimingInjector(config);
		ExceptionInjector exInjector = new ExceptionInjector(config);
		
		SpanCallInjector spanCallInjector = new SpanCallInjector(config);
		SpanMethodInjector spanMethodInjector = new SpanMethodInjector(config);
		
		HttpURLConnectionSendInjector urlSendInjector = new HttpURLConnectionSendInjector(config);
		HttpURLConnectionRecvInjector urlRecvInjector = new HttpURLConnectionRecvInjector(config);
		
		JdbcStatementInjector jdbcStmtInjector = new JdbcStatementInjector(config);
		JdbcExecuteInjector jdbcExeInjector = new JdbcExecuteInjector(config);
		
		ServletServiceInjector servletInjector = new ServletServiceInjector(config);
		
		if (config.isEnableLog()) {
			addOnce(classInjectors,logInjector);
		}

		if (config.isEnableExceptionLog()) {
			addOnce(methodInjectors,exInjector);
		}
		
		if (config.isEnableHttpURLConnectionTrace()) {
			addOnce(callInjectors,urlSendInjector);
			addOnce(callInjectors,urlRecvInjector);
		}
		
		if (config.isEnableServletTrace()) {
			addOnce(callInjectors,servletInjector);
		}
		
		if (config.isEnableJdbcTrace()) {
			addOnce(callInjectors,jdbcStmtInjector);
			addOnce(callInjectors,jdbcExeInjector);
		}
		
		if (config.isEnableTimingLog()) {
			//callInjectors.add(callTimeingInjector); //spanCallInjector has duration msg.
			addOnce(methodInjectors,methodTimeingInjector);
		}
		
		addOnce(callInjectors,spanCallInjector);
		addOnce(methodInjectors,spanMethodInjector);
		
		logger.fine("ClassInjector:"+classInjectors.toString());		
		logger.fine("CallInjector:"+callInjectors.toString());
		logger.fine("MethodInjector:"+methodInjectors.toString());
	}
		
	private CtClass interceptClass(CtClass ctclass, ClassInjector injector){
		if (!injector.isNeedInject(ctclass.getName())) return ctclass;
		try{
			for (String fieldStr : injector.getClassFields(ctclass.getName())){
				CtField ctfield = CtField.make(fieldStr, ctclass);
				ctclass.addField(ctfield);
			}
			//logger.finest("Inject into " + ctclass.getName());
		}catch(CannotCompileException ce){
			logger.fine(ce + " class: " + ctclass.getName() + " injector: " + injector);
		}
		return ctclass;
	}

	private CtMethod interceptCall(CtMethod ctmethod, final CallInjector injector){
		if (cannotInject(ctmethod)) return ctmethod;
		final String className = ctmethod.getDeclaringClass().getName();
		final String methodName = ctmethod.getName();
		try{
			ctmethod.instrument(
				new ExprEditor() {
					public void edit(MethodCall m)
								  throws CannotCompileException{
						if (injector.isNeedCallInject(m.getClassName(), m.getMethodName())){
							String callClassName = m.getClassName();
							String callMethodName = m.getMethodName();
							String wrap =  String.format("{\n%1$s\n\t  $_ = $proceed($$); \n%2$s\n}",  
										injector.getMethodCallBefore(callClassName, callMethodName),
										injector.getMethodCallAfter(callClassName, callMethodName));

		
							logger.finest(String.format("Inject method call  %s.%s in %s.%s \n%s", callClassName, callMethodName, className, methodName, wrap));
							m.replace(wrap);
						}
					}
				});
		}catch(CannotCompileException ce){
			logger.fine(ce + " method: " + className +"." + methodName + " injector: " + injector);
		}
		return ctmethod;
	}
	
	private CtMethod interceptMethod(CtMethod ctmethod, MethodInjector injector){
		if (cannotInject(ctmethod)) return ctmethod;
		
		String className = ctmethod.getDeclaringClass().getName();
		String methodName = ctmethod.getName();
		
		//logger.finest("Inject into " + className + "." + methodName);
		
		boolean needTraceInject = injector.isNeedProcessInject(className, methodName);					
		if (!needTraceInject)  return ctmethod;
		
		ClassPool classPool = ClassPool.getDefault();
		try{
			String[][] vars = injector.getMethodVariables(className, methodName);
			if (vars != null && vars.length > 0) for (String[] var : vars){
				String type = var[0];
				CtClass cttype;
				if ("boolean".equals(type)){
					cttype = CtClass.booleanType;
				}else if("byte".equals(type)){
					cttype = CtClass.	byteType;
				}else if("char".equals(type)){
					cttype = CtClass.charType;
				}else if("double".equals(type)){
					cttype = CtClass.doubleType;
				}else if("float".equals(type)){
					cttype = CtClass.floatType;
				}else if("int".equals(type)){
					cttype = CtClass.intType;
				}else if("long".equals(type)){
					cttype = CtClass.longType;
				}else if("short".equals(type)){
					cttype = CtClass.shortType;
				}else if("void".equals(type)){
					cttype = CtClass.voidType;
				}else{
					cttype = classPool.get(type);
				}
				ctmethod.addLocalVariable(var[1], cttype);
			}

			String start = injector.getMethodProcessStart(className, methodName);
			String end = injector.getMethodProcessReturn(className, methodName);
			String ex = injector.getMethodProcessException(className, methodName);
			String fin = injector.getMethodProcessFinally(className, methodName);
			
			//logger.finest(String.format("Inject method process  %s.%s \n%s \n...\n%s \n...\n%s \n...\n%s", className, methodName, start, end, ex, fin));
			
			if (start!=null && start.trim().length()>0) ctmethod.insertBefore(start);
			if (end!=null && end.trim().length()>0) ctmethod.insertAfter(end);
			if (ex!=null && ex.trim().length()>0) ctmethod.addCatch(ex, classPool.get("java.lang.Exception"), "_$e"); 
			if (fin!=null && fin.trim().length()>0) ctmethod.insertAfter(fin, true);
		}catch(NotFoundException ne){
			logger.fine(ne + " method: " + className +"." + methodName + " injector: " + injector);
		}catch(CannotCompileException ce){
			logger.fine(ce + " method: " + className +"." + methodName + " injector: " + injector);
		}catch(Exception ex){
			logger.fine(ex + " method: " + className +"." + methodName + " injector: " + injector);
		}
		return ctmethod;
	}	
	
	private boolean cannotInject(CtMethod ctmethod){
		if (ctmethod.isEmpty() || Modifier.isNative(ctmethod.getModifiers()) || Modifier.isAbstract(ctmethod.getModifiers())) return true;
		return false;
	}
	
    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain,byte[] classfileBuffer) throws IllegalClassFormatException {
        className = className.replace("/", ".");
        if (!config.isNeedInject(className)) {
            return classfileBuffer;
        }
		//logger.fine("class:"+className+" loader:"+loader+" ContextClassLoader:"+Thread.currentThread().getContextClassLoader().toString());
		CtClass ctclass = null;
        try {
            ClassPool classPool = ClassPool.getDefault();
            ctclass = classPool.makeClass(new ByteArrayInputStream(classfileBuffer));
			ctclass.setName(className);
			
			if (ctclass.isInterface()){
			    return classfileBuffer;
			}
			
			for(ClassInjector injector : classInjectors){
				interceptClass(ctclass, injector);
			}
            for (CtMethod ctmethod : ctclass.getDeclaredMethods()) {
				if (cannotInject(ctmethod)) continue;
				for(CallInjector injector : callInjectors){
					interceptCall(ctmethod, injector);
				}
				for(MethodInjector injector : methodInjectors){
					interceptMethod(ctmethod, injector);
				}
            }
            
            byte[] byteCode = ctclass.toBytecode();
            return byteCode;
        } catch (Exception ex) {
        	logger.fine(ex + " class: " + className);
            return classfileBuffer;
        } finally{
			if (ctclass != null){
				ctclass.detach();
			}
		}
    }

}
