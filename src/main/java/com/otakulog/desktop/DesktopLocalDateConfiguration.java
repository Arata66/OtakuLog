package com.otakulog.desktop;

import org.hibernate.boot.model.TypeContributor;
import org.hibernate.jpa.boot.spi.TypeContributorList;
import org.hibernate.type.descriptor.ValueBinder;
import org.hibernate.type.descriptor.ValueExtractor;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.descriptor.jdbc.BasicBinder;
import org.hibernate.type.descriptor.jdbc.BasicExtractor;
import org.hibernate.type.descriptor.jdbc.DateJdbcType;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@Profile("desktop")
public class DesktopLocalDateConfiguration {

    @Bean
    public HibernatePropertiesCustomizer desktopLocalDateMapping() {
        TypeContributor contributor = (types, registry) -> types.contributeJdbcType(new DesktopLocalDateJdbcType());
        return properties -> properties.put("hibernate.type_contributors", (TypeContributorList) () -> List.of(contributor));
    }

    private static final class DesktopLocalDateJdbcType extends DateJdbcType {
        @Override
        public <X> ValueBinder<X> getBinder(JavaType<X> javaType) {
            if (javaType.getJavaTypeClass() != LocalDate.class) return super.getBinder(javaType);
            // 旧 JDBC Date 经历史历法与本机时区转换会偏移观影日期，桌面库直接传递无时区日期。
            return new BasicBinder<>(javaType, this) {
                @Override
                protected void doBind(PreparedStatement statement, X value, int index, WrapperOptions options) throws SQLException {
                    statement.setObject(index, javaType.unwrap(value, LocalDate.class, options), Types.DATE);
                }

                @Override
                protected void doBind(CallableStatement statement, X value, String name, WrapperOptions options) throws SQLException {
                    statement.setObject(name, javaType.unwrap(value, LocalDate.class, options), Types.DATE);
                }
            };
        }

        @Override
        public <X> ValueExtractor<X> getExtractor(JavaType<X> javaType) {
            if (javaType.getJavaTypeClass() != LocalDate.class) return super.getExtractor(javaType);
            return new BasicExtractor<>(javaType, this) {
                @Override
                protected X doExtract(ResultSet result, int index, WrapperOptions options) throws SQLException {
                    return javaType.wrap(result.getObject(index, LocalDate.class), options);
                }

                @Override
                protected X doExtract(CallableStatement statement, int index, WrapperOptions options) throws SQLException {
                    return javaType.wrap(statement.getObject(index, LocalDate.class), options);
                }

                @Override
                protected X doExtract(CallableStatement statement, String name, WrapperOptions options) throws SQLException {
                    return javaType.wrap(statement.getObject(name, LocalDate.class), options);
                }
            };
        }
    }
}
