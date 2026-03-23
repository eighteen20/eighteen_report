package cn.com._1820.eighteen_report;

import cn.com._1820.eighteen_report.entity.DemoUser;
import cn.com._1820.eighteen_report.repository.DemoUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * DemoUser 批量灌数测试。
 *
 * <p>期望用途：用于“按需灌数”。每次运行前先检查 `demo_user` 表是否已存在 10 万条数据：
 * <ul>
 *   <li>如果已存在（>= 100000），则直接跳过插入；</li>
 *   <li>如果不存在（< 100000），则只插入 10 条演示数据，避免重复灌数导致数据库膨胀。</li>
 * </ul>
 *
 * <p>注意：该测试会真实写入数据库，不会自动回滚。</p>
 */
@SpringBootTest
class DemoUserBatchInsertTest {

    /** 表示“已有足够数据”的阈值：100000 条 */
    private static final long TARGET = 100_000L;
    /** 不满足目标阈值时，最多插入多少条（按你的要求为 10 条） */
    private static final int SEED_COUNT = 10;
    private static final int BATCH_SIZE = 1_000;

    private static final String[] LAST_NAMES = {
            "赵", "钱", "孙", "李", "周", "吴", "郑", "王", "冯", "陈",
            "褚", "卫", "蒋", "沈", "韩", "杨", "朱", "秦", "尤", "许",
            "何", "吕", "施", "张", "孔", "曹", "严", "华", "金", "魏"
    };
    private static final String[] FIRST_NAMES = {
            "伟", "芳", "娜", "敏", "静", "丽", "强", "磊", "洋", "艳",
            "勇", "军", "杰", "娟", "涛", "明", "超", "秀英", "霞", "平",
            "刚", "桂英", "丹", "萍", "鑫", "玲", "宇", "浩", "倩", "雪"
    };
    private static final String[] DEPARTMENTS = {
            "研发部", "产品部", "测试部", "运维部", "销售部", "市场部", "财务部", "人事部", "客服部", "法务部"
    };
    private static final String[] POSITIONS = {
            "工程师", "高级工程师", "产品经理", "测试工程师", "运维工程师",
            "销售经理", "市场专员", "财务专员", "HR专员", "项目经理"
    };
    private static final String[] STATUS = { "在职", "离职" };
    private static final String[] GENDER = { "男", "女" };

    @Autowired
    private DemoUserRepository demoUserRepository;

    /**
     * 按需灌数：若数据库已存在 10 万条则跳过，否则插入 10 条。
     */
    @Test
    void insertSeedUsersWhenMissing() {
        long cur = demoUserRepository.count();
        if (cur >= TARGET) {
            System.out.println("demo_user 已存在 >= 10 万条数据（当前：" + cur + "），跳过插入。");
            return;
        }

        long start = System.currentTimeMillis();
        List<DemoUser> batch = new ArrayList<>(Math.min(BATCH_SIZE, SEED_COUNT));

        for (int i = 1; i <= SEED_COUNT; i++) {
            batch.add(buildOne(i));
            if (batch.size() >= BATCH_SIZE) {
                demoUserRepository.saveAll(batch);
                demoUserRepository.flush();
                batch.clear();
                // 本分支理论上不会走到（因为 SEED_COUNT=10，小于 BATCH_SIZE），保留为通用批处理结构。
                System.out.println("已插入批次，进度: " + i);
            }
        }

        if (!batch.isEmpty()) {
            demoUserRepository.saveAll(batch);
            demoUserRepository.flush();
            System.out.println("已插入: " + SEED_COUNT + " / " + SEED_COUNT);
        }

        long costMs = System.currentTimeMillis() - start;
        System.out.println("插入完成，总耗时(ms): " + costMs);
    }

    private DemoUser buildOne(int i) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String name = LAST_NAMES[random.nextInt(LAST_NAMES.length)] + FIRST_NAMES[random.nextInt(FIRST_NAMES.length)];
        String gender = GENDER[random.nextInt(GENDER.length)];
        int age = random.nextInt(20, 56);
        String department = DEPARTMENTS[random.nextInt(DEPARTMENTS.length)];
        String position = POSITIONS[random.nextInt(POSITIONS.length)];
        BigDecimal salary = BigDecimal.valueOf(random.nextLong(6_000, 50_001));
        String email = "demo_user_" + i + "@example.cn";
        String phone = "13" + String.format("%09d", i % 1_000_000_000);
        LocalDate hireDate = LocalDate.now().minus(random.nextInt(0, 3650), ChronoUnit.DAYS);
        String status = STATUS[random.nextInt(STATUS.length)];

        return DemoUser.builder()
                .name(name)
                .gender(gender)
                .age(age)
                .department(department)
                .position(position)
                .salary(salary)
                .email(email)
                .phone(phone)
                .hireDate(hireDate)
                .status(status)
                .build();
    }
}

