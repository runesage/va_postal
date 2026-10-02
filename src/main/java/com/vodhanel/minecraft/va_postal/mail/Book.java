package com.vodhanel.minecraft.va_postal.mail;

import com.vodhanel.minecraft.va_postal.VA_postal;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

public class Book {
    VA_postal plugin;
    private String author;
    private String title;
    private String[] pages;
    private ItemStack itemstack;
    private BookMeta bookData;
    private boolean new_itemstack = false;
    private boolean valid_book = true;
    private List lpages;

    public Book(VA_postal instance) {
        this.plugin = instance;
    }

    public Book(ItemStack bookItem) {
        this.new_itemstack = false;
        this.itemstack = bookItem;
        this.bookData = (BookMeta)this.itemstack.getItemMeta();

        try {
            this.author = this.bookData.getAuthor();
            this.title = this.bookData.getTitle();
            this.lpages = this.bookData.getPages();
        } catch (Exception e) {
            this.valid_book = false;
        }

        if (this.valid_book) {
            Object[] sPages = this.lpages.toArray();
            this.pages = new String[sPages.length];

            for (int i = 0; i < sPages.length; i++) {
                this.pages[i] = sPages[i].toString();
            }

            this.lpages = null;
            sPages = null;
        }
    }

    public Book(String title, String author, String[] pages) {
        this.new_itemstack = true;
        this.title = title;
        this.author = author;
        this.pages = pages;
    }

    public boolean is_valid() {
        if (!this.valid_book) {
            return false;
        } else if (this.title == null) {
            return false;
        } else if (this.title.isEmpty()) {
            return false;
        } else if (this.author == null) {
            return false;
        } else if (this.author.isEmpty()) {
            return false;
        } else {
            return this.pages == null ? false : this.pages.length != 0;
        }
    }

    public String getAuthor() {
        return this.author == null ? "null" : this.author;
    }

    public void setAuthor(String sAuthor) {
        this.author = sAuthor;
        if (!this.new_itemstack) {
            this.bookData.setAuthor(sAuthor);
        }
    }

    public String getTitle() {
        return this.title == null ? "null" : this.title;
    }

    public boolean setTitle(String title) {
        this.title = title;
        if (!this.new_itemstack) {
            this.bookData.setTitle(title);
        }

        return true;
    }

    public int getPagesSize(int page) {
        int result = -1;
        String spage = this.getPage(page);
        if (spage.trim().length() > 0) {
            result = spage.trim().length();
        }

        return result;
    }

    public int getLineCount(int page) {
        String spage = this.getPage(page);
        String[] parts = spage.split("\n");
        return parts.length;
    }

    public int getPagesCount() {
        return this.pages.length;
    }

    public String[] getPages() {
        return this.pages;
    }

    public String[] getPages_with_blank_last_page() {
        String[] new_array = new String[this.pages.length + 1];

        for (int i = 0; i < this.pages.length; i++) {
            new_array[i] = this.pages[i];
        }

        new_array[new_array.length - 1] = "";
        return new_array;
    }

    public String[] getPages_with_blank_first_page() {
        String[] new_array = new String[this.pages.length + 1];
        new_array[0] = "";

        for (int i = 0; i < this.pages.length; i++) {
            new_array[i + 1] = this.pages[i];
        }

        return new_array;
    }

    public String getPage(int page) {
        int index = page - 1;
        return index < this.pages.length ? this.pages[index] : "null";
    }

    public boolean setPage(int page, String text) {
        int index = page;
        if (index > this.pages.length) {
            String[] sPages = new String[index];

            for (int i = 0; i < sPages.length; i++) {
                if (i < this.pages.length) {
                    sPages[i] = this.pages[i];
                    if (!this.new_itemstack) {
                        this.bookData.setPage(i, this.pages[i]);
                    }
                } else {
                    sPages[i] = " ";
                    if (!this.new_itemstack) {
                        this.bookData.addPage(new String[]{" "});
                    }
                }
            }

            this.pages = sPages;
            sPages = null;
        } else {
            this.pages[index] = text;
            if (!this.new_itemstack) {
                this.bookData.setPage(index, text);
            }
        }

        return true;
    }

    public ItemStack generateItemStack() {
        if (!this.new_itemstack) {
            return this.itemstack;
        }

        ItemStack newbook = new ItemStack(Material.WRITTEN_BOOK, 1);
        BookMeta newBookData = (BookMeta)newbook.getItemMeta();
        newBookData.setAuthor(this.author);
        newBookData.setTitle(this.title);

        for (int i = 0; i < this.pages.length; i++) {
            newBookData.addPage(new String[]{this.pages[i]});
        }

        newbook.setItemMeta(newBookData);
        return newbook;
    }
}
