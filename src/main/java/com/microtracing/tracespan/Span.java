package com.microtracing.tracespan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.log4j.MDC;


public class Span{
	private final static String FQCN = Span.class.getName();
	
	//private static final java.util.logging.Logger logger = java.util.logging.Logger.getLogger(Span.class.getName());
	private static final org.apache.log4j.Logger logger =  org.apache.log4j.LogManager.getLogger(Span.class);  
	
	public static final String TRACE_ID_NAME = "X-B3-TraceId";
	public static final String SPAN_ID_NAME = "X-B3-SpanId";
	public static final String PARENT_ID_NAME = "X-B3-ParentSpanId";
	public static final String SPAN_NAME_NAME = "X-Span-Name";
	
	public static final Set<String> SPAN_HEADERS = new HashSet<String>(
			Arrays.asList(PARENT_ID_NAME, TRACE_ID_NAME,SPAN_ID_NAME, SPAN_NAME_NAME));
	

	public static final String SPAN_START = "SPAN_START";  
	public static final String SPAN_END = "SPAN_END";  
	
	public static final String CLIENT_RECV = "cr";
	public static final String CLIENT_SEND = "cs";
	public static final String SERVER_RECV = "sr";
	public static final String SERVER_SEND = "ss";
	
	public static final Set<String> ONE_OFF_EVENTS = new HashSet<String>(
			Arrays.asList(SPAN_START, CLIENT_SEND, SERVER_SEND)); // SPAN_END, CLIENT_RECV, SERVER_RECV use the last event time
	
	
	private String traceId;
	private String spanId;
	private String name;
	
	private boolean remote;

	private Span parentSpan;
	private Set<Span> childSpans = new HashSet<Span>();
	
	private long startTime;
	private long endTime;
	
	private List<SpanEvent> events = new ArrayList<SpanEvent>(6);

	
	public Span(String traceId, Span parentSpan, String spanId, String operationName){
		this.traceId = traceId;
		this.spanId = spanId == null?genSpanId():spanId;
		this.name = operationName;
		this.parentSpan = parentSpan;
		
	}
	
	public void setTraceId(String traceId) {
		this.traceId = traceId;
	}

	public void setSpanId(String spanId) {
		this.spanId = spanId;
	}

	public void setName(String name) {
		this.name = name;
	}

	
	public boolean isRemote() {
		return remote;
	}

	public void setRemote(boolean remote) {
		this.remote = remote;
	}

	public String getTraceId() {
		return traceId;
	}
	
	public String getParentSpanId() {
		return this.parentSpan == null? null:parentSpan.getSpanId();
	}
	
	public String getSpanId() {
		return spanId;
	}

	public long getStartTime() {
		return startTime;
	}

	public long getEndTime() {
		return endTime;
	}

	public String getName() {
		return name;
	}

	private String genSpanId(){
		String[]  uuid = UUID.randomUUID().toString().split("-");
		return uuid[0]+uuid[3]; // 12 chars
	}	
	

	public Span createChildSpan(String operationName){
		Span child = new Span(this.traceId, this, null, operationName);
		childSpans.add(child);
		return child;
	}		
	
	
	public void setParentSpan(Span parentSpan) {
		this.parentSpan= parentSpan;
	}
	
	public Span getParentSpan() {
		return this.parentSpan;
	}
	
	public Set<Span> getChildSpans() {
		return childSpans;
	}
	
	
	public void start(){
		Tracer.getTracer().setCurrentSpan(this);
		if (this.startTime!=0 || this.isRemote()) {
			logger.debug(this.spanId + " span was already started, will not do it again");
		}else {
			startTime = System.currentTimeMillis();
			log(this.toString() + " started.");
			logEvent(SPAN_START);
		}
	}
	
	public void stop(){
		if (this.startTime==0) {
			logger.warn(this.spanId + " span was not started!");
		}
		endTime = System.currentTimeMillis();
		logFormatEvent(SPAN_END,"spanId=%1$s name=%2$s duration=%3$s", this.spanId, this.name, (endTime-startTime) );
		Tracer.getTracer().setCurrentSpan(this.parentSpan);
	}
	
	public void logEvent(String event) {
		logFormatEvent(event, null);
	}
	
	public void logFormatEvent(String event, String format, Object... params) {
		String msg = null;
		if (format != null) {
			if (params != null) {
				msg = String.format(format, params);
			} else {
				msg = format;
			}
		}
		
		if (ONE_OFF_EVENTS.contains(event)) {
			for (SpanEvent e : events) {
				if (e.getEvent().equals(event)) {
					//if (msg!=null) e.setMsg(e.getMsg() + " " + msg);
					logger.debug(e + " was already annotated, will not do it again");
					return ;
				}
			}
		}
		SpanEvent e = new SpanEvent(this.spanId, System.currentTimeMillis(), event, msg);
		events.add(e);
		log(e.toString());
	}
	
	private void log(String log) {
		MDC.put(Span.TRACE_ID_NAME, this.traceId);
		MDC.put(Span.SPAN_ID_NAME, this.spanId);
		if (logger.isInfoEnabled()) {
			logger.log(FQCN, org.apache.log4j.Level.INFO, log, null);
		}		
	}
	
	@Override
	public boolean equals(Object o) {
		if (o == this) {
			return true;
		}
		if (o instanceof Span) {
			Span span = (Span) o;		
			return (span != null 
					&& span.getTraceId()!=null && span.getTraceId().equals(this.traceId) 
					&& span.getSpanId()!=null && span.getSpanId().equals(this.getSpanId()) );
		}
		return false;
	}
	
	
	public Map<String, String> toMap(){
		Map<String,String> carrier = new HashMap<String,String>();
		carrier.put(Span.TRACE_ID_NAME, this.getTraceId());
		carrier.put(Span.PARENT_ID_NAME, this.getParentSpanId());
		carrier.put(Span.SPAN_ID_NAME, this.getSpanId());
		carrier.put(Span.SPAN_NAME_NAME, this.getName());
		return carrier;
	}
	
	public String toString() {
		StringBuffer sb = new StringBuffer("Span{")   ;
		sb.append("traceId=").append(this.traceId);
		sb.append(", spanId=").append(this.spanId);
		sb.append(", spanName=\"").append(this.name).append("\"");
		if(this.parentSpan!=null) sb.append(", parentId=").append(this.getParentSpanId());
		sb.append(", remote=").append(this.remote);
		sb.append("}");
		return sb.toString();
	}
	
	
	
	
	
	public static Span buildSpan(Map<String, String> carrier) {
		String traceId = carrier.get(Span.TRACE_ID_NAME);
		String parentId = carrier.get(Span.PARENT_ID_NAME);
		String spanId = carrier.get(Span.SPAN_ID_NAME);
		String name = carrier.get(Span.SPAN_NAME_NAME);	
		
		Span parentSpan = parentId==null?null:new Span(traceId,null,parentId, null);
		Span span = new Span(traceId,parentSpan, spanId, name);
		
		return span;
	}
	
	
	
}