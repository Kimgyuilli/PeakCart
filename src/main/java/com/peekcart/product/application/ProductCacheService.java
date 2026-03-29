package com.peekcart.product.application;

import com.peekcart.global.cache.CachedPage;
import com.peekcart.global.exception.ErrorCode;
import com.peekcart.product.application.dto.ProductInfoDto;
import com.peekcart.product.application.dto.ProductListDto;
import com.peekcart.product.domain.exception.ProductException;
import com.peekcart.product.domain.model.Product;
import com.peekcart.product.domain.model.ProductStatus;
import com.peekcart.product.domain.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ProductCacheService {

    private final ProductRepository productRepository;

    @Cacheable(cacheNames = "product", key = "#productId")
    public ProductInfoDto getProductInfo(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductException(ErrorCode.PRD_001));
        return ProductInfoDto.of(product);
    }

    @Cacheable(cacheNames = "products",
            key = "'list:' + #categoryId + ':' + #pageable.pageNumber + ':' + #pageable.pageSize")
    public CachedPage<ProductListDto> getProductList(Long categoryId, Pageable pageable) {
        Page<Product> page;
        if (categoryId != null) {
            page = productRepository.findByCategoryIdAndStatus(categoryId, ProductStatus.ON_SALE, pageable);
        } else {
            page = productRepository.findByStatus(ProductStatus.ON_SALE, pageable);
        }
        return CachedPage.of(page.map(ProductListDto::of));
    }
}
