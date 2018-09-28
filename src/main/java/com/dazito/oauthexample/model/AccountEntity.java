package com.dazito.oauthexample.model;

import com.dazito.oauthexample.model.type.UserRole;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "account_entity")
public class AccountEntity{

    // lombok

    @Id
    @GeneratedValue
    private Long id;

    @Column(name = "name", length = 128)
    private String username;

    @Column(name = "password", length = 128)
    private String password;

    @Enumerated(value = EnumType.STRING)
    @Column(name = "role", length = 128)
    private UserRole role;

    @ManyToOne(targetEntity = Organization.class)
    @JoinColumn(name="organization_id")
    private Organization organization;

    private Boolean isActivated;

    private String email;

    @Column
    private String rootPath;

    AccountEntity(String username, String password){
        this.username = username;
        this.password = password;
    }
}
