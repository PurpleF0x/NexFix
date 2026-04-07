FROM php:8.2-apache

# Ativar módulos necessários do Apache
RUN a2enmod rewrite

# Instalar extensões PHP comuns (se necessário no futuro)
RUN docker-php-ext-install mysqli pdo pdo_mysql

# Copiar os ficheiros do backend para a pasta do servidor
COPY backend/ /var/www/html/

# Garantir que o servidor tem permissão de escrita para a memória JSON
RUN chown -R www-data:www-data /var/www/html/ \
    && chmod -R 755 /var/www/html/

# Expor a porta 80
EXPOSE 80

# Comando para iniciar o Apache
CMD ["apache2-foreground"]
