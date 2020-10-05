package com.tvd12.ezyfoxserver.support.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.tvd12.ezyfox.bean.EzyBeanContext;
import com.tvd12.ezyfox.bean.EzySingletonFactory;
import com.tvd12.ezyfox.binding.EzyUnmarshaller;
import com.tvd12.ezyfox.builder.EzyBuilder;
import com.tvd12.ezyfox.core.annotation.EzyClientRequestController;
import com.tvd12.ezyfox.core.annotation.EzyClientRequestListener;
import com.tvd12.ezyfox.core.exception.EzyBadRequestException;
import com.tvd12.ezyfox.core.util.EzyClientRequestListenerAnnotations;
import com.tvd12.ezyfox.entity.EzyArray;
import com.tvd12.ezyfox.entity.EzyData;
import com.tvd12.ezyfox.util.EzyLoggable;
import com.tvd12.ezyfoxserver.context.EzyZoneChildContext;
import com.tvd12.ezyfoxserver.event.EzyUserRequestEvent;
import com.tvd12.ezyfoxserver.support.asm.EzyRequestHandlersImplementer;
import com.tvd12.ezyfoxserver.support.handler.EzyUserRequestHandler;
import com.tvd12.ezyfoxserver.support.handler.EzyUserRequestHandlerProxy;

@SuppressWarnings({ "unchecked", "rawtypes" })
public abstract class EzyUserRequestSingletonController<
		C extends EzyZoneChildContext, 
		E extends EzyUserRequestEvent>
		extends EzyAbstractUserRequestController{

	private final EzyUnmarshaller unmarshaller;
	private final Map<String, EzyUserRequestHandler> handlers;
	
	protected EzyUserRequestSingletonController(Builder<?> builder) {
		this.unmarshaller = builder.unmarshaller;
		this.handlers = new HashMap<>(builder.getHandlers());
	}
	
	public void handle(C context, E event) {
		EzyArray data = event.getData();
		String cmd = data.get(0, String.class);
		EzyData params = data.get(1, EzyData.class, null);
		EzyUserRequestHandler handler = handlers.get(cmd);
		if(handler == null) {
			logger.warn("has no handler with command: {} from session: {}", cmd, event.getSession().getName());
			return;
		}
		Object handlerData = params;
		Class requestDataType = handler.getDataType();
		if(requestDataType != null) {
			handlerData = unmarshaller.unmarshal(handlerData, requestDataType);
		}
		try {
			preHandle(context, event, data);
			handler.handle(context, event, handlerData);
			postHandle(context, event, data, null);
		}
		catch(EzyBadRequestException e) {
			if(e.isSendToClient()) {
				EzyData errorData = newErrorData(e);
				responseError(context, event, errorData);
			}
			logger.debug("request cmd: {} by session: {} with data: {} error", cmd, event.getSession().getName(), data, e);
			postHandle(context, event, handlerData, e);
		}
		catch(Exception e) {
			postHandle(context, event, handlerData, e);
			throw e;
		}
	}
	
	protected void preHandle(C context, E event, Object data) {}
	protected void postHandle(C context, E event, Object data, Exception e) {}
	
	protected abstract void responseError(C context, E event, EzyData errorData);
	
	public abstract static class Builder<B extends Builder>
			extends EzyLoggable
			implements EzyBuilder<EzyUserRequestSingletonController> {
		
		private EzySingletonFactory singletonFactory;
		private EzyUnmarshaller unmarshaller;
		
		public B beanContext(EzyBeanContext beanContext) {
			this.singletonFactory = beanContext.getSingletonFactory();
			this.unmarshaller = beanContext.getSingleton("unmarshaller", EzyUnmarshaller.class);
			return (B)this;
		}
		
		private Map<String, EzyUserRequestHandler> getHandlers() {
			List<Object> clientRequestListeners = getClientRequestListeners();
			Map<String, EzyUserRequestHandler> handlers = new HashMap<>();
			for(Object listener : clientRequestListeners) {
				Class<?> handleType = listener.getClass();
				EzyClientRequestListener annotation = handleType.getAnnotation(EzyClientRequestListener.class);
				String command = EzyClientRequestListenerAnnotations.getCommand(annotation);
				handlers.put(command, new EzyUserRequestHandlerProxy((EzyUserRequestHandler) listener));
				logger.debug("add command {} and request handler singleton {}", command, listener);
			}
			handlers.putAll(implementClientRequestListeners());
			return handlers;
		}
		
		private List<Object> getClientRequestListeners() {
			return singletonFactory.getSingletons(EzyClientRequestListener.class);
		}
		
		private Map<String, EzyUserRequestHandler> implementClientRequestListeners() {
			EzyRequestHandlersImplementer implementer = new EzyRequestHandlersImplementer();
			return implementer.implement(singletonFactory.getSingletons(EzyClientRequestController.class));
		}
	}
}
