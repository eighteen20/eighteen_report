package cn.com._1820.eighteen_report.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * SPA（单页应用）兜底路由控制器
 *
 * 解决 Vue Router History 模式下，用户直接访问或刷新页面时
 * Spring Boot 找不到对应路由而返回 404 的问题。
 *
 * 原理：将所有前端页面路由（非 /api 路径）forward 到 index.html，
 * 由 Vue Router 在浏览器端处理实际的路由匹配和页面渲染。
 *
 * 注意：
 * - 静态资源（.js/.css/.ico 等）由 Spring Boot 默认静态资源处理器接管，
 *   不会被此控制器拦截（因为静态资源处理优先级更高）。
 * - /api/** 路径由各 ApiXxxController 处理，不经过此控制器。
 */
@Controller
public class SpaController {

    /**
     * 匹配所有前端路由路径，将其 forward 到 Vue 应用的入口文件 index.html。
     * Vue Router 会在浏览器端解析完整 URL 并渲染对应页面。
     *
     * @return forward 指令，将请求内部重定向到 index.html
     */
    @RequestMapping(value = {
            "/",
            "/report",
            "/report/**",
            "/datasource",
            "/datasource/**"
    })
    public String spa() {
        return "forward:/index.html";
    }
}
