package com.cloudprocure.identity.account;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.UuidGenerator;

@Entity // 标记这是一个JPA实体，将映射到数据库表
@Table(name = "user_account") // 指定表名，若不写则默认使用类名
@Getter
@Setter
@ToString
public class UserAccount {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private String subjectId;

    /**
     * 登录账号
     */
    private String loginName;

    /**
     * 加密密码
     */
    @ToString.Exclude
    private String passwordHash;

    /**
     * 状态
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AccountStatus status = AccountStatus.ACTIVE;

    /**
     *账号凭据的版本
     */
    private Long credentialVersion;

    /**
     * 版本号
     */
    @Version
    private Long version;
}
