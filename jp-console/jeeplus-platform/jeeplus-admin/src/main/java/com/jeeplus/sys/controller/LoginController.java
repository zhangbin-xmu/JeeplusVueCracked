/**
 * Copyright &copy; 2021-2026 <a href="http://www.jeeplus.org/">JeePlus</a> All rights reserved.
 */
package com.jeeplus.sys.controller;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import cn.hutool.extra.servlet.ServletUtil;
import com.jeeplus.aop.logging.annotation.ApiLog;
import com.jeeplus.common.redis.RedisUtils;
import com.jeeplus.common.utils.RequestUtils;
import com.jeeplus.common.utils.ResponseUtil;
import com.jeeplus.config.properties.JeePlusProperties;
import com.jeeplus.core.errors.ErrorConstants;
import com.jeeplus.security.jwt.TokenProvider;
import com.jeeplus.security.util.SecurityUtils;
import com.jeeplus.sys.constant.CacheNames;
import com.jeeplus.sys.constant.CommonConstants;
import com.jeeplus.sys.constant.enums.LogTypeEnum;
import com.jeeplus.sys.domain.SysConfig;
import com.jeeplus.sys.model.LoginForm;
import com.jeeplus.sys.service.SysConfigService;
import com.jeeplus.sys.service.UserService;
import com.jeeplus.sys.service.dto.UserDTO;
import com.jeeplus.sys.utils.UserUtils;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.jasig.cas.client.authentication.AttributePrincipal;
import org.jasig.cas.client.validation.Assertion;
import org.jasig.cas.client.validation.Cas20ServiceTicketValidator;
import org.jasig.cas.client.validation.TicketValidationException;
import org.jasig.cas.client.validation.TicketValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.*;

import javax.security.auth.login.AccountException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.util.Date;
import java.util.UUID;

/**
 * ??????Controller
 *
 * @author jeeplus
 * @version 2021-5-31
 */
@Slf4j
@RestController
@Api(tags = "????????????")
public class LoginController {

    @Autowired
    AuthenticationManager authenticationManager;

    @Autowired
    private UserService userService;

    @Autowired
    private RedisUtils redisUtils;
    /**
     * ????????????
     * @param loginForm
     * @return
     */
    @PostMapping("/sys/login")
    @ApiLog(value = "????????????", type = LogTypeEnum.LOGIN)
    @ApiOperation("????????????")
    public ResponseEntity login(@RequestBody LoginForm loginForm) {
        ResponseUtil responseUtil = new ResponseUtil ( );
        String username = loginForm.getUsername ();
        String password = loginForm.getPassword ();
        String code = loginForm.getCode ();
        if(!code.equals ( RedisUtils.getInstance ().get (CacheNames.SYS_CACHE_CODE, loginForm.getUuid ()))){
            throw new AccountExpiredException ( ErrorConstants.LOGIN_ERROR_ERROR_VALIDATE_CODE );
        }
        SecurityUtils.login (username, password, authenticationManager  ); //????????????spring security

        /**
         * ??????????????????
         */
        if(!userService.isEnableLogin ( username )){
            throw new DisabledException ( ErrorConstants.LOGIN_ERROR_FORBID_LOGGED_IN_ELSEWHERE );
        }

        //?????????????????????token
        UserDTO userDTO = UserUtils.getByLoginName ( username );
        String token = TokenProvider.createAccessToken ( username, userDTO.getPassword () );
        responseUtil.add ( TokenProvider.TOKEN, token);
        //??????????????????
        updateUserLoginInfo ( responseUtil, userDTO , token);

        return responseUtil.ok ( );
    }


    /**
     * cas??????
     * vue ??????ticket????????????????????????token
     */
    @ApiLog(value = "????????????", type = LogTypeEnum.ACCESS)
    @RequestMapping("/sys/casLogin")
    public ResponseEntity casLogin(@RequestParam(name = "ticket") String ticket,
                                   @RequestParam(name = "service") String service, @Value("${cas.server-url-prefix}") String casServer) throws Exception {
        //ticket?????????
        TicketValidator ticketValidator = new Cas20ServiceTicketValidator ( casServer );
        ResponseUtil responseUtil = new ResponseUtil ( );
        try {
            // ???CAS??????????????????ticket????????????
            Assertion casAssertion = ticketValidator.validate ( ticket, service );
            // ???CAS??????????????????????????????,??????????????????????????????RememberMe???
            AttributePrincipal casPrincipal = casAssertion.getPrincipal ( );
            String loginName = casPrincipal.getName ( );
            // ?????????????????????
            UserDTO userDTO = UserUtils.getByLoginName ( loginName );
            if ( userDTO != null ) {
                if ( CommonConstants.NO.equals ( userDTO.getLoginFlag ( ) ) ) {
                    throw new LockedException ( ErrorConstants.LOGIN_ERROR_FORBIDDEN );
                }
                // ????????????????????????????????????????????????
//                SecurityUtils.login (userDTO.getLoginName (), userDTO.getPassword (), authenticationManager  );
                String token = TokenProvider.createAccessToken ( userDTO.getLoginName (), userDTO.getPassword () );
                Authentication authentication = TokenProvider.getAuthentication ( token );
                SecurityContextHolder.getContext ( ).setAuthentication ( authentication );

                // ??????????????????
                updateUserLoginInfo ( responseUtil, userDTO, token );

                return responseUtil.ok ();
            } else {
                AuthenticationException e = new UsernameNotFoundException ( ErrorConstants.LOGIN_ERROR_NOTFOUND );
                log.error ( "?????????loginName:" + loginName + "????????????!", e );
                throw e;
            }
        } catch (TicketValidationException e) {
            log.error ( "Unable to validate ticket [" + ticket + "]", e );
            throw new CredentialsExpiredException ( "??????????????????ticket [" + ticket + "]", e );
        }

    }


    private void updateUserLoginInfo(ResponseUtil responseUtil, UserDTO userDTO, String token){

        String username = userDTO.getLoginName ();
        redisUtils.set ( CacheNames.USER_CACHE_TOKEN + username + ":" + token, token  );
        redisUtils.expire ( CacheNames.USER_CACHE_TOKEN + username + ":" + token, JeePlusProperties.newInstance ( ).getEXPIRE_TIME ( ) );
        responseUtil.add ( "oldLoginDate", userDTO.getLoginDate () );
        responseUtil.add ( "oldLoginIp", userDTO.getLoginIp () );
        //??????????????????
        userDTO.setLoginDate ( new Date (  ) );
        userDTO.setLoginIp ( ServletUtil.getClientIP (RequestUtils.getRequest ()) );
        userService.updateUserLoginInfo ( userDTO );

    }



    /**
     * ????????????
     * @param request
     * @param response
     * @return
     */
    @ApiOperation("????????????")
    @ApiLog(value = "????????????", type = LogTypeEnum.LOGIN)
    @GetMapping("/sys/logout")
    public ResponseEntity logout(HttpServletRequest request, HttpServletResponse response) {
        Authentication auth = SecurityUtils.getAuthentication ();
        if ( auth != null ) {
            UserUtils.deleteCache (UserUtils.getCurrentUserDTO () );
            String token = TokenProvider.resolveToken(request);
            redisUtils.delete ( CacheNames.USER_CACHE_TOKEN + TokenProvider.getLoginName ( token ) +":" +token );
            new SecurityContextLogoutHandler ( ).logout ( request, response, auth );
        }
        return ResponseEntity.ok ( "????????????" );
    }


    /**
     * ?????????????????????
     * @throws
     */
    @ApiOperation ("???????????????")
    @ApiLog("???????????????")
    @GetMapping("/sys/getCode")
    public ResponseEntity getCode(){
        //HuTool?????????????????????????????????,???????????????????????????????????????
        LineCaptcha lineCaptcha = CaptchaUtil.createLineCaptcha(116, 36,4,50);
        String uuid = UUID.randomUUID ( ).toString ();
        //??????????????????session
        RedisUtils.getInstance ().set ( CacheNames.SYS_CACHE_CODE, uuid , lineCaptcha.getCode ());
        return ResponseUtil.newInstance ().add ( "codeImg", lineCaptcha.getImageBase64 () ).add ( "uuid", uuid ).ok ();
    }


}
