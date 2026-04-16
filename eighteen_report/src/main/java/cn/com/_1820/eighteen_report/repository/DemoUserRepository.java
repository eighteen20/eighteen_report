package cn.com._1820.eighteen_report.repository;

import cn.com._1820.eighteen_report.entity.DemoUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;


/**
 * 演示用户 JPA 仓库，提供基础 CRUD 操作。
 */
public interface DemoUserRepository extends JpaRepository<DemoUser, Long> {

    /**
     * 按姓名模糊分页查询（忽略大小写），用于示例接口联调筛选条件。
     */
    Page<DemoUser> findByNameContainingIgnoreCase(String name, Pageable pageable);
}
