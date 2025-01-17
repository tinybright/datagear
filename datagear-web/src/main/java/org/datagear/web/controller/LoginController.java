/*
 * Copyright 2018 datagear.tech
 *
 * Licensed under the LGPLv3 license:
 * http://www.gnu.org/licenses/lgpl-3.0.html
 */

package org.datagear.web.controller;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.datagear.web.config.ApplicationProperties;
import org.datagear.web.security.LoginCheckCodeErrorException;
import org.datagear.web.util.OperationMessage;
import org.datagear.web.util.WebUtils;
import org.datagear.web.util.accesslatch.AccessLatch;
import org.datagear.web.util.accesslatch.IpLoginLatch;
import org.datagear.web.util.accesslatch.UsernameLoginLatch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.WebAttributes;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 登录控制器。
 * 
 * @author datagear@163.com
 *
 */
@Controller
@RequestMapping("/login")
public class LoginController extends AbstractController
{
	/**
	 * 登录页
	 */
	public static final String LOGIN_PAGE = "/login";

	/**
	 * 登录参数：用户名
	 */
	public static final String LOGIN_PARAM_USER_NAME = "name";

	/**
	 * 登录参数：密码
	 */
	public static final String LOGIN_PARAM_PASSWORD = "password";

	/**
	 * 登录参数：记住登录
	 */
	public static final String LOGIN_PARAM_REMEMBER_ME = "rememberMe";

	/**
	 * 登录参数：校验码
	 */
	public static final String LOGIN_PARAM_CHECK_CODE = "checkCode";

	public static final String CHECK_CODE_MODULE_LOGIN = "LOGIN";

	@Autowired
	private ApplicationProperties applicationProperties;

	@Autowired
	private IpLoginLatch ipLoginLatch;

	@Autowired
	private UsernameLoginLatch usernameLoginLatch;

	public LoginController()
	{
		super();
	}

	public ApplicationProperties getApplicationProperties()
	{
		return applicationProperties;
	}

	public void setApplicationProperties(ApplicationProperties applicationProperties)
	{
		this.applicationProperties = applicationProperties;
	}

	public IpLoginLatch getIpLoginLatch()
	{
		return ipLoginLatch;
	}

	public void setIpLoginLatch(IpLoginLatch ipLoginLatch)
	{
		this.ipLoginLatch = ipLoginLatch;
	}

	public UsernameLoginLatch getUsernameLoginLatch()
	{
		return usernameLoginLatch;
	}

	public void setUsernameLoginLatch(UsernameLoginLatch usernameLoginLatch)
	{
		this.usernameLoginLatch = usernameLoginLatch;
	}

	/**
	 * 打开登录界面。
	 * 
	 * @return
	 */
	@RequestMapping
	public String login(HttpServletRequest request, HttpServletResponse response)
	{
		request.setAttribute("loginUsername", resolveLoginUsername(request, response));
		request.setAttribute("disableRegister", this.applicationProperties.isDisableRegister());
		request.setAttribute("disableLoginCheckCode", this.applicationProperties.isDisableLoginCheckCode());
		request.setAttribute("currentUser", WebUtils.getUser(request, response).cloneNoPassword());
		setDetectNewVersionScriptAttr(request, response, this.applicationProperties.isDisableDetectNewVersion());

		return "/login";
	}

	@RequestMapping(value = "/success", produces = CONTENT_TYPE_JSON)
	@ResponseBody
	public ResponseEntity<OperationMessage> loginSuccess(HttpServletRequest request, HttpServletResponse response)
	{
		return optMsgSuccessResponseEntity(request, "login.success");
	}

	@RequestMapping(value = "/error", produces = CONTENT_TYPE_JSON)
	@ResponseBody
	public ResponseEntity<OperationMessage> loginError(HttpServletRequest request, HttpServletResponse response)
	{
		AuthenticationException ae = getAuthenticationExceptionWithRemove(request);
		if (ae != null)
		{
			if (ae instanceof LoginCheckCodeErrorException)
			{
				return optMsgFailResponseEntity(request, HttpStatus.UNAUTHORIZED, "checkCodeError");
			}
		}

		int ipLoginRemain = this.ipLoginLatch.remain(request);

		if (AccessLatch.isLatched(ipLoginRemain))
		{
			return optMsgFailResponseEntity(request, HttpStatus.UNAUTHORIZED, "login.ipLoginLatched");
		}

		int usernameLoginRemain = this.usernameLoginLatch.remain(request.getParameter(LOGIN_PARAM_USER_NAME));

		if (AccessLatch.isLatched(usernameLoginRemain))
		{
			return optMsgFailResponseEntity(request, HttpStatus.UNAUTHORIZED, "login.usernameLoginLatched");
		}
		else if (AccessLatch.isNonLatch(usernameLoginRemain))
		{
			return optMsgFailResponseEntity(request, HttpStatus.UNAUTHORIZED, "login.userNameOrPasswordError");
		}
		else if (usernameLoginRemain > 0)
		{
			if (ipLoginRemain >= 0)
				usernameLoginRemain = Math.min(ipLoginRemain, usernameLoginRemain);

			return optMsgFailResponseEntity(request, HttpStatus.UNAUTHORIZED, "login.userNameOrPasswordErrorRemain",
					usernameLoginRemain);
		}
		else
		{
			return optMsgFailResponseEntity(request, HttpStatus.UNAUTHORIZED, "login.userNameOrPasswordError");
		}
	}

	protected AuthenticationException getAuthenticationExceptionWithRemove(HttpServletRequest request)
	{
		// 参考org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler.saveException()

		AuthenticationException authenticationException = (AuthenticationException) request
				.getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);

		if (authenticationException == null)
		{
			authenticationException = (AuthenticationException) request.getSession()
					.getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);

			if (authenticationException != null)
				request.getSession().removeAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);
		}

		return authenticationException;
	}

	protected String resolveLoginUsername(HttpServletRequest request, HttpServletResponse response)
	{
		HttpSession session = request.getSession();

		String username = (String) session.getAttribute(RegisterController.SESSION_KEY_REGISTER_USER_NAME);
		session.removeAttribute(RegisterController.SESSION_KEY_REGISTER_USER_NAME);

		if (username == null)
			username = "";

		return username;
	}

	/**
	 * 将用户名编码为可存储至Cookie的字符串。
	 * 
	 * @param username
	 * @return
	 */
	public static String encodeCookieUserName(String username)
	{
		if (username == null || username.isEmpty())
			return username;

		try
		{
			return URLEncoder.encode(username, "UTF-8");
		}
		catch (UnsupportedEncodingException e)
		{
			throw new ControllerException(e);
		}
	}

	/**
	 * 将{@linkplain #encodeCookieUserName(String)}的用户名解码。
	 * 
	 * @param username
	 * @return
	 */
	public static String decodeCookieUserName(String username)
	{
		if (username == null || username.isEmpty())
			return username;

		try
		{
			return WebUtils.decodeURL(username);
		}
		catch (UnsupportedEncodingException e)
		{
			throw new ControllerException(e);
		}
	}
}
